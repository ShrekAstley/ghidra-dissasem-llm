package ghidrallm.agent;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.config.SettingsStore;
import ghidrallm.ghidra.ContextCollector;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.ghidra.ProgramAccess;
import ghidrallm.ghidra.Resolver;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.knowledge.Note;
import ghidrallm.knowledge.ProgramKeys;
import ghidrallm.llm.*;
import ghidrallm.log.DebugLog;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/**
 * Non-UI orchestration: owns settings, the provider, tools, conversation, proposals and the single
 * background worker. All LLM and Ghidra-heavy work runs on that worker thread, never the Swing EDT.
 */
public class AssistantService implements AutoCloseable {

	private static final Pattern FUNC_MENTION = Pattern.compile("\\b((?:FUN|SUB|sub|thunk_FUN)_[0-9a-fA-F]{4,}|0x[0-9a-fA-F]{4,})\\b");

	private final ProgramAccess access;
	private final SettingsStore settingsStore;
	private final Path dataDir;
	private volatile Settings settings;
	private final DebugLog log = new DebugLog();
	private final DecompilerService decompiler;
	private final ProposalManager proposals;
	private volatile KnowledgeStore knowledge;
	private final LLMProvider provider;
	private volatile ToolRegistry registry;
	private volatile AgentLoop loop;
	private final Conversation conversation = new Conversation();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LocalLLM-Agent");
		t.setDaemon(true);
		return t;
	});
	private volatile CancellationToken active;
	private volatile ArchitectureReport lastReport;
	private volatile LastAsk lastAsk;

	private record LastAsk(String display, String prompt, boolean withContext) {}

	/** Outcome of the last connectivity probe. */
	public record Status(boolean connected, String model, String message, List<String> models) {}

	public AssistantService(ProgramAccess access, SettingsStore store, Path dataDir) {
		this.access = access;
		this.settingsStore = store;
		this.dataDir = dataDir;
		this.settings = store.load();
		this.decompiler = new DecompilerService(60);
		this.proposals = new ProposalManager(access::program, decompiler);
		this.provider = new LMStudioProvider(() -> settings);
		openKnowledge();
		rebuildTools();
		proposals.setOnApplied(p -> recordApproved(p));
	}

	private void openKnowledge() {
		KnowledgeStore old = knowledge;
		knowledge = null;
		if (old != null) {
			old.close();
		}
		if (!settings.persistKnowledge) {
			return;
		}
		try {
			knowledge = KnowledgeStore.open(dataDir.resolve("knowledge.db"));
		}
		catch (SQLException | RuntimeException e) {
			log.error("Knowledge database unavailable (continuing without persistence)", e);
		}
	}

	private void rebuildTools() {
		registry = Tools.create(settings);
		loop = new AgentLoop(provider, registry, log);
	}

	// ---- accessors ----------------------------------------------------------------------------

	public Settings settings() {
		return settings;
	}

	public DebugLog log() {
		return log;
	}

	public ProposalManager proposals() {
		return proposals;
	}

	public KnowledgeStore knowledge() {
		return knowledge;
	}

	public Conversation conversation() {
		return conversation;
	}

	public LLMProvider provider() {
		return provider;
	}

	public ArchitectureReport lastReport() {
		return lastReport;
	}

	public ToolRegistry registry() {
		return registry;
	}

	public DecompilerService decompiler() {
		return decompiler;
	}

	public ProgramAccess access() {
		return access;
	}

	public String programKey() {
		Program p = access.program();
		return p == null ? null : ProgramKeys.of(p);
	}

	public void updateSettings(Settings s) throws java.io.IOException {
		boolean knowledgeChanged = s.persistKnowledge != settings.persistKnowledge;
		s.normalize();
		settingsStore.save(s);
		settings = s;
		rebuildTools();
		if (knowledgeChanged) {
			openKnowledge();
		}
		log.info("Settings updated (endpoint " + s.endpoint + ", model '" + s.model + "', tool mode " + s.toolMode + ")");
	}

	// ---- connection ---------------------------------------------------------------------------

	/** Blocking; call from a background thread. */
	public Status checkConnection() {
		try {
			List<String> models = provider.listModels();
			if (models.isEmpty()) {
				return new Status(true, "", "Connected, but no model is loaded in LM Studio.", models);
			}
			String m = settings.model.isBlank() ? models.get(0) : settings.model;
			boolean present = models.contains(m);
			return new Status(true, m, present ? "" : "Configured model '" + m + "' is not loaded.", models);
		}
		catch (LLMException e) {
			return new Status(false, "", e.getMessage(), List.of());
		}
	}

	public ConnectionReport testConnection() {
		ConnectionReport r = provider.testConnection(settings.model);
		log.info("Connection test:\n" + r);
		return r;
	}

	// ---- chat ---------------------------------------------------------------------------------

	public boolean isBusy() {
		return busy;
	}

	private volatile boolean busy;

	public void stop() {
		CancellationToken t = active;
		if (t != null) {
			t.cancel();
		}
	}

	public void clearConversation() {
		stop();
		conversation.clear();
		lastAsk = null;
	}

	/** Asks a question. Returns immediately; {@code done} is invoked on the worker thread. */
	public Future<?> ask(String display, String prompt, boolean withContext, AgentListener listener,
			Consumer<AgentResult> done) {
		lastAsk = new LastAsk(display, prompt, withContext);
		return submit(tok -> runAsk(tok, display, prompt, withContext, listener), done);
	}

	/** Re-runs the last question after discarding its previous answer. */
	public Future<?> regenerate(AgentListener listener, Consumer<AgentResult> done) {
		LastAsk la = lastAsk;
		if (la == null) {
			return CompletableFuture.completedFuture(null);
		}
		conversation.popLastTurn();
		return submit(tok -> runAsk(tok, la.display(), la.prompt(), la.withContext(), listener), done);
	}

	public String lastQuestion() {
		return lastAsk == null ? null : lastAsk.display();
	}

	private Future<?> submit(java.util.function.Function<CancellationToken, AgentResult> job, Consumer<AgentResult> done) {
		CancellationToken token = new CancellationToken();
		stop();
		active = token;
		return worker.submit(() -> {
			busy = true;
			AgentResult r;
			try {
				r = job.apply(token);
			}
			catch (RuntimeException e) {
				log.error("Unexpected failure", e);
				r = new AgentResult(AgentResult.Status.ERROR, "", "", 0, 0, Usage.NONE, 0, "Unexpected error: " + e.getMessage());
			}
			finally {
				busy = false;
			}
			if (done != null) {
				done.accept(r);
			}
		});
	}

	private ToolContext toolContext(CancellationToken token) {
		return new ToolContext(access::program, access::currentAddress, decompiler, proposals, knowledge,
			settings, log, token);
	}

	private AgentResult runAsk(CancellationToken token, String display, String prompt, boolean withContext,
			AgentListener listener) {
		Settings s = settings;
		AgentLoop lp = loop;
		ToolContext tc = toolContext(token);
		try {
			if (access.program() == null) {
				return new AgentResult(AgentResult.Status.ERROR, "", "", 0, 0, Usage.NONE, 0,
					"No program is open in Ghidra. Open a binary first.");
			}
			Prepared prep = prepare(prompt, withContext, lp, tc, s, listener);
			AgentRequest rq = new AgentRequest(conversation, display, prep.focus, prep.content, prep.keys, "", true, s,
				tc, token, listener);
			return lp.run(rq);
		}
		catch (CancellationToken.CancelledException e) {
			return new AgentResult(AgentResult.Status.CANCELLED, "", "", 0, 0, Usage.NONE, 0, "Cancelled");
		}
	}

	private record Prepared(String content, String focus, List<String> keys) {}

	private Prepared prepare(String prompt, boolean withContext, AgentLoop lp, ToolContext tc, Settings s,
			AgentListener listener) {
		StringBuilder sb = new StringBuilder();
		String focus = "";
		List<String> keys = List.of();
		Program p = access.program();
		sb.append("[Ghidra state] program=").append(p.getName());
		Address cur = access.currentAddress();
		if (cur != null) {
			sb.append(" cursor=").append(cur);
		}
		String sel = access.selectionDescription();
		if (sel != null) {
			sb.append(" selection=").append(sel);
		}
		sb.append('\n');
		if (withContext && s.includeContextOnSelection) {
			listener.onStatus("Collecting context from Ghidra…");
			try {
				Function f = mentionedFunction(prompt, p, tc);
				if (f == null && cur != null) {
					f = p.getFunctionManager().getFunctionContaining(cur);
				}
				ContextCollector cc = new ContextCollector(lp.registry());
				List<ContextItem> items;
				if (f != null) {
					focus = f.getName() + "@" + f.getEntryPoint();
					sb.append("Selected function: ").append(f.getName(true)).append(" @ ").append(f.getEntryPoint()).append('\n');
					items = cc.forFunction(tc, f, s.contextBudgetTokens < 8192);
				}
				else if (cur != null) {
					items = cc.forAddress(tc, cur);
				}
				else {
					items = List.of();
				}
				int avail = s.contextBudgetTokens - s.maxOutputTokens - lp.fixedOverheadTokens(s) - Text.estimateTokens(prompt) - 300;
				int blockTokens = Math.max(500, Math.min(14000, (int) (avail * 0.55)));
				ContextManager.Block b = new ContextManager().assemble(items, conversation.sentContextKeys(), blockTokens);
				if (!b.text().isBlank()) {
					sb.append("\n[Context retrieved from Ghidra - treat as ground truth for facts it shows]\n").append(b.text()).append("\n");
				}
				keys = b.includedKeys();
				log.log(DebugLog.Category.INFO, "Context block: ~" + b.estimatedTokens() + " tokens, " + b.includedKeys().size() +
					" items, omitted=" + b.omittedTitles().size() + ", truncated=" + b.truncatedTitles().size(), b.text(), true);
			}
			catch (RuntimeException e) {
				log.error("Context collection failed", e);
				sb.append("[Context collection failed: ").append(e.getMessage()).append(". Use tools to inspect.]\n");
			}
		}
		sb.append("\n[Question]\n").append(prompt);
		return new Prepared(sb.toString(), focus, keys);
	}

	private Function mentionedFunction(String prompt, Program p, ToolContext tc) {
		Matcher m = FUNC_MENTION.matcher(prompt);
		while (m.find()) {
			try {
				return Resolver.function(p, m.group(1));
			}
			catch (ToolException e) {
				// not a function in this program; keep looking
			}
		}
		return null;
	}

	// ---- program analysis ---------------------------------------------------------------------

	/** Progress callback: (current, total, message). */
	public interface Progress {
		void update(int current, int total, String message);
	}

	/**
	 * Progressive whole-program analysis: deterministic facts → per-function summaries (isolated,
	 * small contexts) → synthesis of subsystems from the summaries only. Never sends the whole binary.
	 */
	public Future<?> analyzeProgram(Progress progress, Consumer<AgentResult> done) {
		return submit(tok -> runProgramAnalysis(tok, progress), done);
	}

	private AgentResult runProgramAnalysis(CancellationToken token, Progress progress) {
		Settings s = settings;
		AgentLoop lp = loop;
		ToolContext tc = toolContext(token);
		long t0 = System.nanoTime();
		Usage usage = Usage.NONE;
		try {
			Program p = access.program();
			if (p == null) {
				return new AgentResult(AgentResult.Status.ERROR, "", "", 0, 0, Usage.NONE, 0, "No program is open in Ghidra.");
			}
			progress.update(0, 1, "Collecting program facts…");
			String key = ProgramKeys.of(p);
			StringBuilder facts = new StringBuilder();
			facts.append(run(lp, tc, "get_program_metadata", "{}")).append(run(lp, tc, "get_entry_points", "{}"));
			facts.append(run(lp, tc, "list_imports", "{\"limit\":60}"));
			String topXrefs = run(lp, tc, "list_functions", "{\"order\":\"xrefs\",\"limit\":" + s.programAnalysisMaxFunctions + "}");
			String topSize = run(lp, tc, "list_functions", "{\"order\":\"size\",\"limit\":" + Math.max(5, s.programAnalysisMaxFunctions / 3) + "}");

			LinkedHashMap<String, Function> picks = new LinkedHashMap<>();
			for (String name : List.of("entry", "main", "_start", "WinMain", "DllMain")) {
				for (Function f : Resolver.functionsNamed(p, name)) {
					picks.putIfAbsent(f.getEntryPoint().toString(), f);
				}
			}
			collectFunctions(p, topXrefs, picks);
			collectFunctions(p, topSize, picks);
			List<Function> chosen = new ArrayList<>(picks.values());
			if (chosen.size() > s.programAnalysisMaxFunctions) {
				chosen = chosen.subList(0, s.programAnalysisMaxFunctions);
			}

			Settings sub = s.copy();
			sub.maxToolCalls = Math.min(4, s.maxToolCalls);
			sub.maxAgentSteps = 6;
			StringBuilder summaries = new StringBuilder();
			Conversation scratch = new Conversation();
			int i = 0;
			for (Function f : chosen) {
				token.throwIfCancelled();
				progress.update(++i, chosen.size() + 1, "Summarizing " + f.getName() + " (" + i + "/" + chosen.size() + ")");
				String fkey = f.getName() + "@" + f.getEntryPoint();
				ContextCollector cc = new ContextCollector(lp.registry());
				List<ContextItem> items = cc.forFunction(tc, f, true);
				int avail = s.contextBudgetTokens - s.maxOutputTokens - lp.fixedOverheadTokens(sub) - 400;
				ContextManager.Block b = new ContextManager().assemble(items, Set.of(), Math.max(500, (int) (avail * 0.5)));
				String content = "[Context]\n" + b.text() + "\n\n[Task]\nSummarize function " + fkey + " for a program-architecture overview. " +
					"Reply in exactly this format and nothing else:\nSUBSYSTEM: <2-4 word label for the role this code plays, inferred from evidence>\n" +
					"SUMMARY: <at most 2 sentences; hedge uncertain claims>\nCONFIDENCE: <CONFIRMED|LIKELY|POSSIBLE|UNKNOWN>";
				AgentResult r = lp.run(new AgentRequest(scratch, fkey, fkey, content, b.includedKeys(), "", false, sub, tc, token, AgentListener.NONE));
				scratch.clear();
				usage = usage.plus(r.usage());
				if (!r.ok()) {
					if (r.status() == AgentResult.Status.CANCELLED) {
						throw new CancellationToken.CancelledException();
					}
					return r;
				}
				String sum = field(r.answer(), "SUMMARY");
				String sub_ = field(r.answer(), "SUBSYSTEM");
				String conf = field(r.answer(), "CONFIDENCE");
				if (sum.isEmpty()) {
					sum = Text.oneLine(r.answer(), 220);
				}
				summaries.append("- ").append(fkey).append(" [").append(sub_.isEmpty() ? "unclassified" : sub_).append("] ")
						.append(Text.oneLine(sum, 220)).append('\n');
				saveNote(key, Note.Kind.FUNCTION_SUMMARY, fkey, f.getEntryPoint().toString(), sum + (sub_.isEmpty() ? "" : " (subsystem: " + sub_ + ")"), conf);
			}

			token.throwIfCancelled();
			progress.update(chosen.size() + 1, chosen.size() + 1, "Synthesizing architecture…");
			String synth = "[Program facts]\n" + Text.truncate(facts.toString(), 6000) + "\n[Function summaries (model-generated, unverified)]\n" +
				Text.truncate(summaries.toString(), 9000) + "\n[Task]\nInfer the program's actual subsystems and overall architecture from the evidence above. " +
				"Do not assume any particular categories exist; only include subsystems supported by the summaries, imports or entry flow. " +
				"You may use tools to check specifics. Respond with a short overview paragraph, then a JSON object in a ```json fence:\n" +
				"{\"overview\":\"entry -> init -> runtime -> shutdown style flow, hedged\",\"subsystems\":[{\"name\":\"\",\"description\":\"\",\"confidence\":\"LIKELY\",\"functions\":[\"name@addr\"],\"children\":[]}]}\n" +
				"Use only function names that appear in the summaries. Nest children where a subsystem has distinct parts.";
			Settings synthS = s.copy();
			synthS.maxToolCalls = Math.min(6, s.maxToolCalls);
			Conversation c2 = new Conversation();
			AgentResult r = lp.run(new AgentRequest(c2, "Analyze Program", "", synth, List.of(), "", false, synthS, tc, token, AgentListener.NONE));
			if (!r.ok()) {
				return r;
			}
			ArchitectureReport rep = ArchitectureReport.parse(r.answer());
			lastReport = rep;
			saveNote(key, Note.Kind.ARCHITECTURE, "program", null, r.answer(), "POSSIBLE");
			for (ArchitectureReport.Node n : rep.root.children) {
				saveNote(key, Note.Kind.SUBSYSTEM, n.name, null, n.description + " Functions: " + String.join(", ", n.functions), n.confidence);
			}
			usage = usage.plus(r.usage());
			return new AgentResult(AgentResult.Status.COMPLETED, rep.toTreeText() + "\n" + rep.overview, "", 0, chosen.size() + 1, usage,
				(System.nanoTime() - t0) / 1_000_000, null);
		}
		catch (CancellationToken.CancelledException e) {
			return new AgentResult(AgentResult.Status.CANCELLED, "", "", 0, 0, usage, (System.nanoTime() - t0) / 1_000_000, "Cancelled");
		}
	}

	private static final Pattern FN_LINE = Pattern.compile("^\\s+(\\S+) @ (\\S+)", Pattern.MULTILINE);

	private static void collectFunctions(Program p, String listing, Map<String, Function> out) {
		Matcher m = FN_LINE.matcher(listing);
		while (m.find()) {
			try {
				Function f = Resolver.function(p, m.group(2));
				out.putIfAbsent(f.getEntryPoint().toString(), f);
			}
			catch (ToolException e) {
				// skip
			}
		}
	}

	private static String field(String text, String name) {
		Matcher m = Pattern.compile("(?im)^\\s*" + name + "\\s*:\\s*(.+)$").matcher(text);
		return m.find() ? m.group(1).trim() : "";
	}

	private String run(AgentLoop lp, ToolContext tc, String tool, String args) {
		return lp.registry().execute(tool, args, tc).text() + "\n";
	}

	/** Asks the agent to drill into one subsystem of the last report. */
	public Future<?> drillDown(ArchitectureReport.Node node, AgentListener l, Consumer<AgentResult> done) {
		String prompt = "Drill into the subsystem '" + node.name + "' (" + node.description + "). Candidate functions: " +
			String.join(", ", node.functions) + ". Inspect the most important ones with tools, explain how this subsystem works, " +
			"what its entry points and data structures are, and how it connects to the rest of the program. Label evidence CONFIRMED/LIKELY/POSSIBLE/UNKNOWN.";
		return ask("Drill down: " + node.name, prompt, false, l, done);
	}

	private void saveNote(String key, Note.Kind kind, String subject, String addr, String text, String conf) {
		KnowledgeStore k = knowledge;
		if (k == null) {
			return;
		}
		try {
			k.add(key, kind, subject, addr, text, Note.Source.AI, conf);
		}
		catch (SQLException e) {
			log.error("Could not store note", e);
		}
	}

	private void recordApproved(ghidrallm.changes.ChangeProposal p) {
		KnowledgeStore k = knowledge;
		Program prog = access.program();
		if (k == null || prog == null) {
			return;
		}
		try {
			k.add(ProgramKeys.of(prog), Note.Kind.APPROVED_CHANGE, p.title(), p.addressText(), p.knowledgeSummary(), Note.Source.APPROVED, "CONFIRMED");
		}
		catch (SQLException e) {
			log.error("Could not store approved change", e);
		}
	}

	@Override
	public void close() {
		stop();
		worker.shutdownNow();
		decompiler.close();
		if (knowledge != null) {
			knowledge.close();
		}
	}
}

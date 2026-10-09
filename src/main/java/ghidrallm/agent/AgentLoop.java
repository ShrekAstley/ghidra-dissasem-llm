package ghidrallm.agent;

import java.util.*;

import com.google.gson.*;

import ghidrallm.config.Settings;
import ghidrallm.llm.*;
import ghidrallm.log.DebugLog;
import ghidrallm.log.DebugLog.Category;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/**
 * The controlled agent loop: model → (tool calls → Ghidra executes → results) → model … → answer.
 * Every iteration is bounded by hard limits: total tool calls, steps ("recursion"), repeated identical
 * calls, result size, per-request timeout and wall-clock timeout. Works with native OpenAI-style tool
 * calling and, when the model/server lacks it, a prompted {@code <tool_call>} protocol.
 */
public class AgentLoop {

	private final LLMProvider provider;
	private final ToolRegistry registry;
	private final DebugLog log;
	private final ContextManager contextManager = new ContextManager();
	/** Sticky result of AUTO detection. */
	private volatile boolean autoPrompted = false;
	private volatile String resolvedModel;

	public AgentLoop(LLMProvider provider, ToolRegistry registry, DebugLog log) {
		this.provider = provider;
		this.registry = registry;
		this.log = log;
	}

	public ToolRegistry registry() {
		return registry;
	}

	public boolean isPrompted(Settings s) {
		return switch (s.toolMode) {
			case "PROMPTED" -> true;
			case "NATIVE" -> false;
			default -> autoPrompted;
		};
	}

	/** Estimated fixed prompt cost: system prompt plus (native) tool schemas. */
	public int fixedOverheadTokens(Settings s) {
		boolean prompted = isPrompted(s);
		int n = Text.estimateTokens(Prompts.system(registry, prompted));
		if (!prompted) {
			for (ToolSpec t : registry.specs(s.contextBudgetTokens < 24000)) {
				n += Text.estimateTokens(t.name() + t.description() + t.parametersSchema());
			}
		}
		return n;
	}

	public void resetModelCache() {
		resolvedModel = null;
		autoPrompted = false;
	}

	public AgentResult run(AgentRequest rq) {
		long t0 = System.nanoTime();
		Settings s = rq.settings();
		AgentListener ls = rq.listener() == null ? AgentListener.NONE : rq.listener();
		CancellationToken cancel = rq.cancel();
		Conversation conv = rq.conversation();
		Conversation.Turn turn = conv.startTurn(rq.displayUser(), rq.focus(), ChatMessage.user(rq.userContent()),
			rq.contextKeys());
		Usage total = Usage.NONE;
		int calls = 0, step = 0, blockedStreak = 0, malformedStreak = 0, contextRetries = 0;
		double budgetFactor = 1.0;
		boolean forceFinal = false;
		Map<String, Integer> seen = new HashMap<>();
		long deadline = System.nanoTime() + s.agentTimeoutSeconds * 1_000_000_000L;
		try {
			String model = resolveModel(s, ls);
			while (true) {
				cancel.throwIfCancelled();
				if (System.nanoTime() > deadline) {
					forceFinal = true;
				}
				if (step >= s.maxAgentSteps || calls >= s.maxToolCalls) {
					forceFinal = true;
				}
				if (step >= s.maxAgentSteps + 2) {
					return finish(conv, turn, "(Stopped: agent step limit reached without a final answer.)",
						"", calls, step, total, t0, AgentResult.Status.LIMIT_REACHED, null);
				}
				step++;
				boolean prompted = isPrompted(s);
				if (forceFinal && !endsWithNudge(turn)) {
					conv.append(turn, ChatMessage.user(
						"Limits reached. Provide your FINAL answer now using only the evidence already gathered. " +
							"Label uncertainty (CONFIRMED / LIKELY / POSSIBLE / UNKNOWN) and list what you could not verify."));
				}
				List<ToolSpec> specs = (!prompted && !forceFinal) ? registry.specs(s.contextBudgetTokens < 24000) : List.of();
				ChatResponse resp;
				try {
					ls.onStatus(forceFinal ? "Composing final answer…" : "Thinking… (step " + step + ")");
					resp = callModel(rq, model, specs, prompted, budgetFactor);
				}
				catch (LLMException e) {
					if (e.getKind() == LLMException.Kind.CONTEXT_TOO_LARGE && contextRetries++ < 3) {
						budgetFactor *= 0.7;
						log.log(Category.AGENT, "Context too large; retrying with smaller budget", "factor=" + budgetFactor, false);
						step--;
						continue;
					}
					if (e.getKind() == LLMException.Kind.BAD_REQUEST && !specs.isEmpty() && s.toolMode.equals("AUTO") && provider.canFallBackToPrompted()) {
						autoPrompted = true;
						log.log(Category.AGENT, "Server rejected native tool calling; switching to prompted tool protocol", e.getMessage(), false);
						ls.onStatus("Model lacks native tool calling; using prompted protocol");
						step--;
						continue;
					}
					throw e;
				}
				total = total.plus(effectiveUsage(resp, rq, specs));
				ls.onUsage(resp.usage(), total);

				ResponseParser.Parsed rp = ResponseParser.parse(resp.content());
				List<ToolCall> reqCalls = resp.toolCalls();
				List<String> errors = List.of();
				String visible = rp.answer();
				if (reqCalls.isEmpty()) {
					ToolCallParser.Parsed tp = ToolCallParser.parse(rp.answer());
					reqCalls = tp.calls();
					errors = tp.errors();
					visible = tp.visibleText();
				}
				if (forceFinal || (reqCalls.isEmpty() && errors.isEmpty())) {
					String answer = visible.isBlank() && forceFinal && !reqCalls.isEmpty()
							? "(The model kept requesting tools after limits were reached.)" : visible;
					if ("length".equals(resp.finishReason())) {
						answer += "\n\n[Response was cut off by the maximum output token limit.]";
					}
					if (answer.isBlank()) {
						answer = "(The model returned an empty response.)";
					}
					return finish(conv, turn, answer, rp.hiddenReasoning(), calls, step, total, t0,
						forceFinal && calls >= s.maxToolCalls ? AgentResult.Status.LIMIT_REACHED : AgentResult.Status.COMPLETED, null);
				}
				if (!visible.isBlank()) {
					ls.onAssistantText(visible);
				}

				if (reqCalls.isEmpty()) {
					// only malformed calls
					if (++malformedStreak > 3) {
						conv.popLastTurn();
						log.log(Category.ERROR, "Model repeatedly produced malformed tool calls", "", false);
						return new AgentResult(AgentResult.Status.ERROR, "", "", calls, step, total, ms(t0),
							"The model repeatedly produced malformed tool calls. Try switching Tool mode to NATIVE or use a model with better instruction following.");
					}
					conv.append(turn, ChatMessage.assistant(resp.content()));
					conv.append(turn, ChatMessage.user("Your tool call could not be parsed: " + String.join(" ", errors) +
						" Use exactly: <tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>"));
					log.log(Category.AGENT, "Malformed tool call", String.join("\n", errors), false);
					continue;
				}
				malformedStreak = 0;

				boolean native_ = !resp.toolCalls().isEmpty();
				if (native_) {
					conv.append(turn, ChatMessage.assistantWithCalls(visible, reqCalls).withProviderBlocks(resp.providerBlocks()));
				}
				else {
					conv.append(turn, ChatMessage.assistant(resp.content()));
				}
				StringBuilder promptedResults = new StringBuilder();
				boolean anyExecuted = false;
				for (ToolCall tc : reqCalls) {
					cancel.throwIfCancelled();
					String sig = tc.name() + ":" + canonical(tc.argumentsJson());
					int times = seen.merge(sig, 1, Integer::sum);
					String result;
					boolean isError;
					long ms = 0;
					if (calls >= s.maxToolCalls) {
						result = "ERROR: tool-call budget (" + s.maxToolCalls + ") exhausted. Answer now with the evidence gathered.";
						isError = true;
						forceFinal = true;
					}
					else if (times > s.maxRepeatedCalls) {
						result = "ERROR: you already made this exact call " + (times - 1) +
							" time(s) and the result will not change. Use different arguments, a different tool, or give your answer.";
						isError = true;
						blockedStreak++;
						log.log(Category.AGENT, "Blocked repeated call: " + tc.name(), tc.argumentsJson(), false);
					}
					else {
						ls.onToolCall(tc.name(), tc.argumentsJson());
						log.log(Category.TOOL_CALL, tc.name() + " " + Text.oneLine(tc.argumentsJson(), 200), tc.argumentsJson(), false);
						long ts = System.nanoTime();
						ToolResult tr = registry.execute(tc.name(), tc.argumentsJson(), rq.toolContext().withCancel(cancel));
						ms = (System.nanoTime() - ts) / 1_000_000;
						calls++;
						anyExecuted = true;
						blockedStreak = 0;
						result = Text.truncate(tr.text(), s.maxToolResultChars);
						if (result.length() < tr.text().length()) {
							result += "\n[result truncated to " + s.maxToolResultChars + " chars; request narrower data]";
						}
						isError = tr.error();
						log.log(Category.TOOL_RESULT, tc.name() + (isError ? " ERROR" : " ok") + " (" + ms + " ms, " + tr.text().length() + " chars)",
							tr.text(), true);
						ls.onToolResult(tc.name(), result, isError, ms);
					}
					if (native_) {
						conv.append(turn, ChatMessage.tool(tc.id(), tc.name(), result));
					}
					else {
						promptedResults.append("<tool_response name=\"").append(tc.name()).append("\" id=\"").append(tc.id())
								.append("\">\n").append(result).append("\n</tool_response>\n");
					}
				}
				if (!native_) {
					conv.append(turn, ChatMessage.user(promptedResults.toString().stripTrailing()));
				}
				if (!anyExecuted && blockedStreak >= 3) {
					forceFinal = true;
				}
			}
		}
		catch (CancellationToken.CancelledException e) {
			conv.popLastTurn();
			log.log(Category.AGENT, "Agent run cancelled", "", false);
			return new AgentResult(AgentResult.Status.CANCELLED, "", "", calls, step, total, ms(t0), "Cancelled");
		}
		catch (LLMException e) {
			conv.popLastTurn();
			if (e.getKind() == LLMException.Kind.CANCELLED) {
				return new AgentResult(AgentResult.Status.CANCELLED, "", "", calls, step, total, ms(t0), "Cancelled");
			}
			log.log(Category.ERROR, "LLM error: " + e.getKind(), e.getMessage(), false);
			return new AgentResult(AgentResult.Status.ERROR, "", "", calls, step, total, ms(t0), e.getMessage());
		}
		catch (RuntimeException e) {
			conv.popLastTurn();
			log.error("Unexpected agent failure", e);
			return new AgentResult(AgentResult.Status.ERROR, "", "", calls, step, total, ms(t0),
				"Unexpected internal error: " + e.getMessage() + " (see the Debug Log tab)");
		}
	}

	private AgentResult finish(Conversation conv, Conversation.Turn turn, String answer, String hidden, int calls,
			int step, Usage total, long t0, AgentResult.Status status, String error) {
		conv.append(turn, ChatMessage.assistant(answer));
		conv.finishTurn(turn, answer);
		log.log(Category.TIMING, "Agent finished: " + status + ", " + calls + " tool calls, " + step + " steps, " + ms(t0) + " ms, tokens " + total.totalTokens(), "", false);
		return new AgentResult(status, answer, hidden, calls, step, total, ms(t0), error);
	}

	private static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	private static boolean endsWithNudge(Conversation.Turn turn) {
		var l = turn.messagesView();
		return !l.isEmpty() && l.get(l.size() - 1).content() != null && l.get(l.size() - 1).content().startsWith("Limits reached.");
	}

	private Usage effectiveUsage(ChatResponse resp, AgentRequest rq, List<ToolSpec> specs) {
		if (resp.usage().totalTokens() > 0) {
			return resp.usage();
		}
		int out = Text.estimateTokens(resp.content());
		return new Usage(0, out, out);
	}

	private String resolveModel(Settings s, AgentListener ls) throws LLMException {
		if (s.model != null && !s.model.isBlank()) {
			return s.model;
		}
		if (provider.requiresExplicitModel()) {
			throw new LLMException(LLMException.Kind.NO_MODEL, "choose a model for this provider in Settings");
		}
		String m = resolvedModel;
		if (m == null) {
			ls.onStatus("Looking up loaded model…");
			List<String> models = provider.listModels();
			if (models.isEmpty()) {
				throw new LLMException(LLMException.Kind.NO_MODEL, null);
			}
			m = models.stream().filter(x -> !x.toLowerCase().contains("embed")).findFirst().orElse(models.get(0));
			resolvedModel = m;
		}
		return m;
	}

	private ChatResponse callModel(AgentRequest rq, String model, List<ToolSpec> specs, boolean prompted,
			double budgetFactor) throws LLMException {
		Settings s = rq.settings();
		String sys = Prompts.system(registry, prompted);
		if (rq.extraSystem() != null && !rq.extraSystem().isBlank()) {
			sys += "\n" + rq.extraSystem();
		}
		String mem = rq.useHistory() ? rq.conversation().memoryBlock() : "";
		if (!mem.isEmpty()) {
			sys += "\n" + mem;
		}
		int overhead = 0;
		for (ToolSpec t : specs) {
			overhead += Text.estimateTokens(t.name() + t.description() + t.parametersSchema());
		}
		int budget = (int) ((s.contextBudgetTokens - s.maxOutputTokens) * budgetFactor);
		List<List<ChatMessage>> turns = rq.useHistory() ? rq.conversation().turnMessages() : lastTurnOnly(rq.conversation());
		List<ChatMessage> msgs = contextManager.fit(ChatMessage.system(sys), turns, budget, overhead);
		ChatRequest req = new ChatRequest(model, msgs, specs, s.temperature, s.maxOutputTokens);
		int estPrompt = overhead;
		for (ChatMessage m : msgs) {
			estPrompt += Text.estimateTokens(m.content()) + 6;
		}
		log.log(Category.LLM_REQUEST, "Request to " + provider.id() + " model=" + model + " messages=" + msgs.size() +
			" ~" + estPrompt + " prompt tokens" + (specs.isEmpty() ? (prompted ? " (prompted tools)" : " (no tools)") : " + " + specs.size() + " native tools"),
			dump(msgs), true);
		ChatResponse resp = provider.chat(req, rq.cancel());
		log.log(Category.LLM_RESPONSE, "Response in " + resp.elapsedMillis() + " ms, finish=" + resp.finishReason() +
			", tokens p/c/t=" + resp.usage().promptTokens() + "/" + resp.usage().completionTokens() + "/" + resp.usage().totalTokens() +
			(resp.toolCalls().isEmpty() ? "" : ", " + resp.toolCalls().size() + " native tool call(s)"), resp.content(), true);
		return resp;
	}

	private static List<List<ChatMessage>> lastTurnOnly(Conversation c) {
		List<List<ChatMessage>> all = c.turnMessages();
		return all.isEmpty() ? all : List.of(all.get(all.size() - 1));
	}

	private static String dump(List<ChatMessage> msgs) {
		StringBuilder sb = new StringBuilder();
		for (ChatMessage m : msgs) {
			sb.append('[').append(m.role()).append(m.name() != null ? ":" + m.name() : "").append("]\n").append(m.content());
			for (ToolCall c : m.toolCalls()) {
				sb.append("\n  tool_call ").append(c.name()).append(' ').append(c.argumentsJson());
			}
			sb.append("\n\n");
		}
		return sb.toString();
	}

	/** Order-independent form of an argument object, so {a:1,b:2} and {b:2,a:1} count as repeats. */
	static String canonical(String json) {
		try {
			JsonElement e = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
			return canon(e);
		}
		catch (JsonParseException ex) {
			return json;
		}
	}

	private static String canon(JsonElement e) {
		if (e.isJsonObject()) {
			TreeMap<String, String> m = new TreeMap<>();
			e.getAsJsonObject().entrySet().forEach(en -> m.put(en.getKey(), canon(en.getValue())));
			return m.toString();
		}
		if (e.isJsonArray()) {
			StringBuilder sb = new StringBuilder("[");
			e.getAsJsonArray().forEach(x -> sb.append(canon(x)).append(','));
			return sb.append(']').toString();
		}
		if (e.isJsonPrimitive()) {
			return e.getAsString().trim().toLowerCase();
		}
		return "null";
	}
}

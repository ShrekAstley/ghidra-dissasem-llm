package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidrallm.agent.*;
import ghidrallm.config.Settings;
import ghidrallm.config.SettingsStore;
import ghidrallm.ghidra.ProgramAccess;
import ghidrallm.knowledge.Note;
import ghidrallm.knowledge.ProgramKeys;
import ghidrallm.testutil.MockLmStudio;
import ghidrallm.testutil.TestPrograms;

/** End-to-end: mock LM Studio + real headless Ghidra program + real tools. */
class AssistantServiceTest {
	@TempDir
	Path tmp;
	MockLmStudio server;
	Program program;
	AssistantService svc;
	volatile Address cursor;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockLmStudio();
		program = TestPrograms.build(this);
		cursor = program.getAddressFactory().getDefaultAddressSpace().getAddress(0x401004);
		SettingsStore store = new SettingsStore(tmp);
		Settings s = new Settings();
		s.endpoint = server.endpoint();
		s.requestTimeoutSeconds = 20;
		store.save(s);
		svc = new AssistantService(new ProgramAccess() {
			public Program program() {
				return program;
			}

			public Address currentAddress() {
				return cursor;
			}
		}, store, tmp);
	}

	@AfterEach
	void tearDown() {
		svc.close();
		server.close();
		program.release(this);
	}

	private AgentResult ask(String q) throws Exception {
		AtomicReference<AgentResult> out = new AtomicReference<>();
		svc.ask(q, q, true, AgentListener.NONE, out::set).get(60, TimeUnit.SECONDS);
		return out.get();
	}

	private static String userContent(JsonObject req) {
		JsonArray m = req.getAsJsonArray("messages");
		for (int i = m.size() - 1; i >= 0; i--) {
			if (m.get(i).getAsJsonObject().get("role").getAsString().equals("user")) {
				return m.get(i).getAsJsonObject().get("content").getAsString();
			}
		}
		return "";
	}

	@Test
	void verticalSlice_selectFunction_sendContext_displayAnswer() throws Exception {
		server.then(MockLmStudio.chatText("FUN_00401000 calls FUN_00401040 with \"config.dat\". LIKELY a config loader."));
		AgentResult r = ask("What does this function do?");
		assertEquals(AgentResult.Status.COMPLETED, r.status(), r.error());
		assertTrue(r.answer().contains("config loader"));
		String sent = userContent(server.requests.get(0));
		assertTrue(sent.contains("Selected function: FUN_00401000"), sent);
		assertTrue(sent.contains("Decompiler output of FUN_00401000"), "decompiled code is sent");
		assertTrue(sent.contains("Assembly of FUN_00401000") && sent.contains("SUB RSP"), "assembly is sent");
		assertTrue(sent.contains("config.dat"), "referenced string is sent");
		assertTrue(sent.contains("FUN_00401060"), "caller is sent");
		assertTrue(sent.contains("What does this function do?"));
		assertFalse(sent.contains("FUN_00401060 @ 00401060\n    SUB"), "other functions' code is not dumped");
	}

	@Test
	void agentInspectsGhidraThenAnswers_andFollowUpDoesNotResendContext() throws Exception {
		server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"FUN_00401040\"}"))
				.then(MockLmStudio.chatText("FUN_00401040 is called only by FUN_00401000."))
				.then(MockLmStudio.chatText("The caller passes a path and flags."));
		AgentResult r = ask("Who calls FUN_00401040?");
		assertEquals(1, r.toolCalls());
		String toolMsg = server.requests.get(1).getAsJsonArray("messages").toString();
		assertTrue(toolMsg.contains("Callers of FUN_00401040") && toolMsg.contains("FUN_00401000"), "real tool result fed back");
		// mention routes context to FUN_00401040 even though the cursor is elsewhere
		assertTrue(userContent(server.requests.get(0)).contains("Selected function: FUN_00401040"));
		ask("And what does the caller pass?");
		assertTrue(server.requests.get(2).getAsJsonArray("messages").toString().contains("Who calls FUN_00401040?"), "history kept");
	}

	@Test
	void sameFunctionContextIsNotResentOnFollowUp() throws Exception {
		server.then(MockLmStudio.chatText("It loads a config.")).then(MockLmStudio.chatText("Called by FUN_00401060."));
		ask("What does this function do?");
		assertTrue(userContent(server.requests.get(0)).contains("### Assembly of FUN_00401000"));
		ask("What calls it?");
		String second = userContent(server.requests.get(1));
		assertFalse(second.contains("### "), "no context sections resent: " + second);
		assertFalse(second.contains("SUB RSP"), "no assembly resent");
		assertFalse(second.contains("config.dat"), "no strings resent");
		assertTrue(second.contains("Already provided earlier in this conversation and unchanged"), second);
		assertTrue(second.contains("What calls it?"));
		// the earlier answer stays visible to the model (conversation memory)
		assertTrue(server.requests.get(1).getAsJsonArray("messages").toString().contains("It loads a config."));
		// moving the cursor to another function brings in that function's context
		cursor = program.getAddressFactory().getDefaultAddressSpace().getAddress(0x401044);
		server.then(MockLmStudio.chatText("Leaf."));
		ask("And this one?");
		assertTrue(userContent(server.requests.get(2)).contains("### Assembly of FUN_00401040"));
	}

	@Test
	void agentProposesRename_userApproves_programChanges_knowledgeRecorded() throws Exception {
		server.then(MockLmStudio.nativeToolCall("c1", "propose_rename_function",
			"{\"function\":\"FUN_00401000\",\"new_name\":\"load_config\",\"reason\":\"opens config.dat\",\"confidence\":\"LIKELY\"}"))
				.then(MockLmStudio.chatText("I proposed renaming it to load_config."));
		ask("Suggest a better name.");
		assertEquals("FUN_00401000", program.getFunctionManager().getFunctionAt(cursor.getAddressSpace().getAddress(0x401000)).getName());
		assertEquals(1, svc.proposals().pending().size());
		var res = svc.proposals().approve(svc.proposals().pending().get(0).id(), false);
		assertTrue(res.success(), res.message());
		assertEquals("load_config", program.getFunctionManager().getFunctionAt(cursor.getAddressSpace().getAddress(0x401000)).getName());
		List<Note> notes = svc.knowledge().list(ProgramKeys.of(program), Note.Kind.APPROVED_CHANGE, 10);
		assertEquals(1, notes.size());
		assertEquals(Note.Source.APPROVED, notes.get(0).source());
		assertTrue(notes.get(0).text().contains("load_config"));
	}

	@Test
	void stopCancelsInFlightRequest() throws Exception {
		server.then(new MockLmStudio.Reply(200, "{}", 8000));
		AtomicReference<AgentResult> out = new AtomicReference<>();
		Future<?> f = svc.ask("q", "q", true, AgentListener.NONE, out::set);
		Thread.sleep(700);
		long t0 = System.currentTimeMillis();
		svc.stop();
		f.get(5, TimeUnit.SECONDS);
		assertEquals(AgentResult.Status.CANCELLED, out.get().status());
		assertTrue(System.currentTimeMillis() - t0 < 3000);
		assertTrue(svc.conversation().isEmpty());
	}

	@Test
	void regenerateRerunsLastQuestionReplacingAnswer() throws Exception {
		server.then(MockLmStudio.chatText("first answer")).then(MockLmStudio.chatText("second answer"));
		ask("Explain");
		AtomicReference<AgentResult> out = new AtomicReference<>();
		svc.regenerate(AgentListener.NONE, out::set).get(30, TimeUnit.SECONDS);
		assertEquals("second answer", out.get().answer());
		assertEquals(1, svc.conversation().turns().size());
		String all = server.requests.get(1).getAsJsonArray("messages").toString();
		assertFalse(all.contains("first answer"), "old answer discarded");
		assertTrue(userContent(server.requests.get(1)).contains("Decompiler output"), "context re-sent after pop");
	}

	@Test
	void noProgramGivesClearError() throws Exception {
		program = null;
		AgentResult r = ask("hi");
		assertEquals(AgentResult.Status.ERROR, r.status());
		assertTrue(r.error().contains("No program is open"));
		program = TestPrograms.build(this);
	}

	@Test
	void connectionStatus() {
		var st = svc.checkConnection();
		assertTrue(st.connected());
		assertEquals("test-model", st.model());
		server.close();
		assertFalse(svc.checkConnection().connected());
	}

	@Test
	void settingsPersistAndRebuildTools() throws Exception {
		Settings s = svc.settings().copy();
		s.allowProposalTools = false;
		s.temperature = 0.7;
		svc.updateSettings(s);
		assertTrue(svc.registry().find("propose_rename_function").isEmpty());
		Settings reloaded = new SettingsStore(tmp).load();
		assertEquals(0.7, reloaded.temperature, 1e-9);
		assertFalse(reloaded.allowProposalTools);
	}

	@Test
	void analyzeProgramProducesArchitectureAndStoresKnowledge() throws Exception {
		server.fallback(req -> {
			String u = userContent(req);
			if (u.contains("Summarize function")) {
				return MockLmStudio.chatText("SUBSYSTEM: Configuration loading\nSUMMARY: Loads config.dat and stores the result.\nCONFIDENCE: LIKELY");
			}
			return MockLmStudio.chatText("The program loads configuration then runs main.\n```json\n" +
				"{\"overview\":\"main -> config\",\"subsystems\":[{\"name\":\"Configuration\",\"description\":\"loads config.dat\",\"confidence\":\"LIKELY\",\"functions\":[\"FUN_00401000@00401000\"],\"children\":[]}]}\n```");
		});
		List<String> progress = new CopyOnWriteArrayList<>();
		AtomicReference<AgentResult> out = new AtomicReference<>();
		svc.analyzeProgram((c, t, m) -> progress.add(c + "/" + t + " " + m), out::set).get(120, TimeUnit.SECONDS);
		assertEquals(AgentResult.Status.COMPLETED, out.get().status(), out.get().error());
		assertNotNull(svc.lastReport());
		assertEquals("Configuration", svc.lastReport().root.children.get(0).name);
		assertTrue(out.get().answer().contains("└── Configuration"));
		assertFalse(progress.isEmpty());
		var notes = svc.knowledge().list(ProgramKeys.of(program), null, 100);
		assertTrue(notes.stream().anyMatch(n -> n.kind() == Note.Kind.FUNCTION_SUMMARY && n.text().contains("Loads config.dat")));
		assertTrue(notes.stream().anyMatch(n -> n.kind() == Note.Kind.SUBSYSTEM));
		assertTrue(notes.stream().anyMatch(n -> n.kind() == Note.Kind.ARCHITECTURE));
		// per-function requests are isolated and small (no giant whole-program dump)
		for (JsonObject r : server.requests) {
			assertTrue(r.toString().length() < 40_000, "request size " + r.toString().length());
		}
	}

	@Test
	void analyzeProgramIsCancellable() throws Exception {
		server.fallback(req -> new MockLmStudio.Reply(200, MockLmStudio.chatText("SUBSYSTEM: x\nSUMMARY: y").body(), 600));
		AtomicReference<AgentResult> out = new AtomicReference<>();
		Future<?> f = svc.analyzeProgram((c, t, m) -> {}, out::set);
		Thread.sleep(1200);
		svc.stop();
		f.get(20, TimeUnit.SECONDS);
		assertEquals(AgentResult.Status.CANCELLED, out.get().status());
	}
}

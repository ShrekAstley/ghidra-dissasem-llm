package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;

import com.google.gson.*;

import ghidrallm.agent.*;
import ghidrallm.config.Settings;
import ghidrallm.llm.*;
import ghidrallm.log.DebugLog;
import ghidrallm.testutil.MockLmStudio;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;

class AgentLoopTest {
	private MockLmStudio server;
	private Settings settings;
	private DebugLog log;
	private AtomicInteger executions;
	private ToolRegistry registry;
	private AgentLoop loop;
	private Conversation conv;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockLmStudio();
		settings = new Settings();
		settings.endpoint = server.endpoint();
		settings.requestTimeoutSeconds = 10;
		settings.maxToolCalls = 5;
		settings.maxRepeatedCalls = 1;
		log = new DebugLog();
		executions = new AtomicInteger();
		registry = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		registry.register(new SimpleTool("get_callers", ToolPermission.READ_PROGRAM, "Callers of a function.",
			List.of(ToolParam.string("function", "f", true)), (c, a) -> {
				executions.incrementAndGet();
				return ToolResult.ok("callers of " + a.str("function") + ": main, init");
			}));
		registry.register(new SimpleTool("get_big", ToolPermission.READ_PROGRAM, "Big output.", List.of(),
			(c, a) -> ToolResult.ok("X".repeat(50_000))));
		loop = new AgentLoop(new LMStudioProvider(() -> settings), registry, log);
		conv = new Conversation();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private AgentResult run(String q, CancellationToken tok, AgentListener l) {
		ToolContext tc = new ToolContext(() -> null, () -> null, null, null, null, settings, log, tok);
		return loop.run(new AgentRequest(conv, q, "focus", q, List.of(), "", true, settings, tc, tok, l));
	}

	private AgentResult run(String q) {
		return run(q, new CancellationToken(), AgentListener.NONE);
	}

	private static JsonObject lastMessage(JsonObject req) {
		JsonArray m = req.getAsJsonArray("messages");
		return m.get(m.size() - 1).getAsJsonObject();
	}

	@Test
	void multiStepNativeToolCalls() {
		server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"FUN_1\"}"))
				.then(MockLmStudio.nativeToolCall("c2", "get_callers", "{\"function\":\"FUN_2\"}"))
				.then(MockLmStudio.chatText("FUN_1 is called by main. LIKELY an initializer.", 100, 20));
		List<String> events = new ArrayList<>();
		AgentResult r = run("What calls FUN_1?", new CancellationToken(), new AgentListener() {
			@Override
			public void onToolCall(String name, String args) {
				events.add("call:" + name);
			}

			@Override
			public void onToolResult(String name, String result, boolean error, long ms) {
				events.add("result:" + result);
			}
		});
		assertEquals(AgentResult.Status.COMPLETED, r.status(), r.error());
		assertEquals(2, r.toolCalls());
		assertEquals(3, r.steps());
		assertEquals(2, executions.get());
		assertTrue(r.answer().contains("LIKELY"));
		assertEquals(List.of("call:get_callers", "result:callers of FUN_1: main, init", "call:get_callers", "result:callers of FUN_2: main, init"), events);
		// second request must contain the tool result as a tool-role message tied to the call id
		JsonObject second = server.requests.get(1);
		JsonObject last = lastMessage(second);
		assertEquals("tool", last.get("role").getAsString());
		assertEquals("c1", last.get("tool_call_id").getAsString());
		assertTrue(second.has("tools"), "native tools advertised");
		assertTrue(r.usage().totalTokens() > 0);
	}

	@Test
	void promptedProtocolWhenConfigured() {
		settings.toolMode = "PROMPTED";
		server.then(MockLmStudio.taggedToolCall("get_callers", "{\"function\":\"FUN_9\"}"))
				.then(MockLmStudio.chatText("done"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status());
		assertEquals(1, executions.get());
		JsonObject first = server.requests.get(0);
		assertFalse(first.has("tools"), "no native tools in prompted mode");
		assertTrue(first.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("<tool_call>"));
		String fed = lastMessage(server.requests.get(1)).get("content").getAsString();
		assertTrue(fed.startsWith("<tool_response name=\"get_callers\""), fed);
		assertEquals("user", lastMessage(server.requests.get(1)).get("role").getAsString());
	}

	@Test
	void autoModeFallsBackWhenServerRejectsTools() {
		server.then(MockLmStudio.error(400, "This model does not support tools"))
				.then(MockLmStudio.taggedToolCall("get_callers", "{\"function\":\"A\"}"))
				.then(MockLmStudio.chatText("final"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status(), r.error());
		assertTrue(loop.isPrompted(settings));
		assertEquals(1, executions.get());
		assertTrue(server.requests.get(0).has("tools"));
		assertFalse(server.requests.get(1).has("tools"));
	}

	@Test
	void nativeModeStillUnderstandsTagsEmittedInContent() {
		server.then(MockLmStudio.taggedToolCall("get_callers", "{\"function\":\"A\"}")).then(MockLmStudio.chatText("ok"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status());
		assertEquals(1, executions.get());
	}

	@Test
	void maxToolCallsForcesFinalAnswer() {
		settings.maxToolCalls = 2;
		AtomicInteger n = new AtomicInteger();
		server.fallback(req -> {
			if (req.has("tools")) {
				return MockLmStudio.nativeToolCall("c" + n.incrementAndGet(), "get_callers", "{\"function\":\"F" + n.get() + "\"}");
			}
			return MockLmStudio.chatText("Final answer from gathered evidence.");
		});
		AgentResult r = run("q");
		assertEquals(2, executions.get(), "exactly the budget executes");
		assertEquals(AgentResult.Status.LIMIT_REACHED, r.status());
		assertEquals("Final answer from gathered evidence.", r.answer());
		JsonObject lastReq = server.requests.get(server.requests.size() - 1);
		assertFalse(lastReq.has("tools"), "tools withheld on the forced final request");
		assertTrue(lastMessage(lastReq).get("content").getAsString().contains("Limits reached"));
	}

	@Test
	void repeatedIdenticalCallsAreBlockedNotReexecuted() {
		settings.maxRepeatedCalls = 1;
		server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"A\"}"))
				.then(MockLmStudio.nativeToolCall("c2", "get_callers", "{ \"function\" : \"a\" }"))
				.then(MockLmStudio.chatText("answer"));
		AgentResult r = run("q");
		assertEquals(1, executions.get());
		assertEquals(AgentResult.Status.COMPLETED, r.status());
		String fed = lastMessage(server.requests.get(2)).get("content").getAsString();
		assertTrue(fed.contains("exact call"), fed);
	}

	@Test
	void infiniteToolLoopTerminates() {
		settings.maxToolCalls = 100;
		settings.maxAgentSteps = 6;
		settings.maxRepeatedCalls = 1;
		server.fallback(req -> req.has("tools")
				? MockLmStudio.nativeToolCall("c", "get_callers", "{\"function\":\"same\"}")
				: MockLmStudio.chatText("gave up, summary"));
		AgentResult r = run("q");
		assertTrue(r.ok(), String.valueOf(r.error()));
		assertEquals(1, executions.get());
		assertTrue(server.requests.size() <= 10);
	}

	@Test
	void modelThatNeverStopsCallingToolsEvenWhenWithheldIsCut() {
		settings.maxToolCalls = 1;
		server.fallback(req -> MockLmStudio.taggedToolCall("get_callers", "{\"function\":\"x" + System.nanoTime() + "\"}"));
		AgentResult r = run("q");
		assertNotNull(r);
		assertTrue(server.requests.size() <= settings.maxAgentSteps + 3);
		assertEquals(1, executions.get());
		assertTrue(r.status() == AgentResult.Status.LIMIT_REACHED || r.status() == AgentResult.Status.COMPLETED);
	}

	@Test
	void unknownToolAndBadArgsAreReportedBackToModel() {
		server.then(MockLmStudio.nativeToolCall("c1", "format_disk", "{}"))
				.then(MockLmStudio.nativeToolCall("c2", "get_callers", "{}"))
				.then(MockLmStudio.chatText("fine"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status());
		assertEquals(0, executions.get());
		assertTrue(lastMessage(server.requests.get(1)).get("content").getAsString().contains("Unknown tool 'format_disk'"));
		assertTrue(lastMessage(server.requests.get(2)).get("content").getAsString().contains("missing required argument"));
	}

	@Test
	void malformedToolCallGetsCorrectionThenRecovers() {
		settings.toolMode = "PROMPTED";
		server.then(MockLmStudio.chatText("<tool_call>{broken</tool_call>"))
				.then(MockLmStudio.taggedToolCall("get_callers", "{\"function\":\"A\"}"))
				.then(MockLmStudio.chatText("done"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status());
		assertEquals(1, executions.get());
		assertTrue(lastMessage(server.requests.get(1)).get("content").getAsString().contains("could not be parsed"));
	}

	@Test
	void repeatedlyMalformedCallsEndInError() {
		settings.toolMode = "PROMPTED";
		server.fallback(req -> MockLmStudio.chatText("<tool_call>{broken</tool_call>"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.ERROR, r.status());
		assertTrue(conv.isEmpty(), "failed turn not kept in history");
	}

	@Test
	void toolResultsAreTruncatedToLimit() {
		settings.maxToolResultChars = 1000;
		server.then(MockLmStudio.nativeToolCall("c1", "get_big", "{}")).then(MockLmStudio.chatText("ok"));
		run("q");
		String fed = lastMessage(server.requests.get(1)).get("content").getAsString();
		assertTrue(fed.length() < 1400, "len " + fed.length());
		assertTrue(fed.contains("truncated"));
	}

	@Test
	void cancellationStopsRunAndDropsTurn() throws Exception {
		server.then(new MockLmStudio.Reply(200, "{}", 5000));
		CancellationToken tok = new CancellationToken();
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(400);
			}
			catch (InterruptedException e) {
				// ignore
			}
			tok.cancel();
		});
		t.start();
		long t0 = System.currentTimeMillis();
		AgentResult r = run("q", tok, AgentListener.NONE);
		assertEquals(AgentResult.Status.CANCELLED, r.status());
		assertTrue(System.currentTimeMillis() - t0 < 4000);
		assertTrue(conv.isEmpty());
	}

	@Test
	void cancellationBetweenToolCalls() {
		CancellationToken tok = new CancellationToken();
		server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"A\"}")).then(MockLmStudio.chatText("never"));
		AgentResult r = run("q", tok, new AgentListener() {
			@Override
			public void onToolResult(String name, String result, boolean error, long ms) {
				tok.cancel();
			}
		});
		assertEquals(AgentResult.Status.CANCELLED, r.status());
		assertEquals(1, server.requests.size());
	}

	@Test
	void contextTooLargeRetriesWithSmallerBudget() {
		server.then(MockLmStudio.error(400, "Context length exceeded"))
				.then(MockLmStudio.chatText("ok after shrink"));
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.COMPLETED, r.status(), r.error());
		assertEquals(2, server.requests.size());
	}

	@Test
	void llmFailuresSurfaceFriendlyErrors() {
		server.close();
		settings.endpoint = "http://127.0.0.1:1/v1";
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.ERROR, r.status());
		assertTrue(r.error().contains("LM Studio"), r.error());
		assertFalse(r.error().contains("Exception"));
	}

	@Test
	void noModelLoadedIsExplained() throws Exception {
		server.models = List.of();
		AgentResult r = run("q");
		assertEquals(AgentResult.Status.ERROR, r.status());
		assertTrue(r.error().toLowerCase().contains("no model"));
	}

	@Test
	void reasoningBlocksAreStrippedFromAnswer() {
		server.then(MockLmStudio.chatText("<think>hmm</think>Visible answer"));
		AgentResult r = run("q");
		assertEquals("Visible answer", r.answer());
		assertEquals("hmm", r.hiddenReasoning());
	}

	@Test
	void followUpsSeeHistoryAndSessionMemory() {
		server.then(MockLmStudio.chatText("It loads assets.")).then(MockLmStudio.chatText("Three callers."));
		run("What does FUN_1 do?");
		run("What calls it?");
		JsonArray msgs = server.requests.get(1).getAsJsonArray("messages");
		String all = msgs.toString();
		assertTrue(all.contains("What does FUN_1 do?"));
		assertTrue(all.contains("It loads assets."));
		assertTrue(msgs.get(0).getAsJsonObject().get("content").getAsString().contains("SESSION MEMORY"));
	}

	@Test
	void logsRequestsToolsAndTimingWithSensitiveFlags() {
		server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"A\"}")).then(MockLmStudio.chatText("ok"));
		run("q");
		var entries = log.snapshot();
		assertTrue(entries.stream().anyMatch(e -> e.category() == DebugLog.Category.LLM_REQUEST && e.sensitive()));
		assertTrue(entries.stream().anyMatch(e -> e.category() == DebugLog.Category.TOOL_CALL));
		assertTrue(entries.stream().anyMatch(e -> e.category() == DebugLog.Category.TOOL_RESULT && e.sensitive()));
		assertTrue(entries.stream().anyMatch(e -> e.category() == DebugLog.Category.TIMING));
		String redacted = log.export(true);
		assertFalse(redacted.contains("callers of A"));
		assertTrue(redacted.contains("redacted"));
		assertTrue(log.export(false).contains("callers of A"));
	}
}

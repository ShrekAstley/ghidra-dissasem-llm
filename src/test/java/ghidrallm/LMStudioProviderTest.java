package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.*;

import com.google.gson.*;

import ghidrallm.config.Settings;
import ghidrallm.llm.*;
import ghidrallm.testutil.MockLmStudio;
import ghidrallm.util.CancellationToken;

class LMStudioProviderTest {
	private MockLmStudio server;
	private Settings settings;
	private LMStudioProvider provider;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockLmStudio();
		settings = new Settings();
		settings.endpoint = server.endpoint();
		settings.requestTimeoutSeconds = 5;
		provider = new LMStudioProvider(() -> settings);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private ChatRequest simple() {
		return new ChatRequest("test-model", List.of(ChatMessage.system("sys"), ChatMessage.user("hi")), null, 0.2, 128);
	}

	@Test
	void listsModels() throws Exception {
		server.models = List.of("a", "b");
		assertEquals(List.of("a", "b"), provider.listModels());
	}

	@Test
	void serializesRequest() throws Exception {
		server.then(MockLmStudio.chatText("hello"));
		ToolSpec spec = new ToolSpec("get_callers", "desc", JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject());
		provider.chat(new ChatRequest("m", List.of(ChatMessage.system("s"), ChatMessage.user("u"),
			ChatMessage.assistantWithCalls("", List.of(new ToolCall("c1", "get_callers", "{\"function\":\"x\"}"))),
			ChatMessage.tool("c1", "get_callers", "result")), List.of(spec), 0.3, 256), new CancellationToken());
		JsonObject req = server.requests.get(0);
		assertEquals("m", req.get("model").getAsString());
		assertEquals(0.3, req.get("temperature").getAsDouble(), 1e-9);
		assertEquals(256, req.get("max_tokens").getAsInt());
		assertFalse(req.get("stream").getAsBoolean());
		JsonArray msgs = req.getAsJsonArray("messages");
		assertEquals("system", msgs.get(0).getAsJsonObject().get("role").getAsString());
		JsonObject asst = msgs.get(2).getAsJsonObject();
		assertEquals("get_callers", asst.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		JsonObject tool = msgs.get(3).getAsJsonObject();
		assertEquals("tool", tool.get("role").getAsString());
		assertEquals("c1", tool.get("tool_call_id").getAsString());
		assertEquals("get_callers", req.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
	}

	@Test
	void parsesTextAndUsage() throws Exception {
		server.then(MockLmStudio.chatText("answer", 10, 5));
		ChatResponse r = provider.chat(simple(), new CancellationToken());
		assertEquals("answer", r.content());
		assertEquals(15, r.usage().totalTokens());
		assertTrue(r.toolCalls().isEmpty());
	}

	@Test
	void parsesNativeToolCall() throws Exception {
		server.then(MockLmStudio.nativeToolCall("id9", "get_callers", "{\"function\":\"main\"}"));
		ChatResponse r = provider.chat(simple(), new CancellationToken());
		assertEquals(1, r.toolCalls().size());
		assertEquals("get_callers", r.toolCalls().get(0).name());
		assertEquals("id9", r.toolCalls().get(0).id());
		assertEquals("tool_calls", r.finishReason());
	}

	@Test
	void mapsHttpErrors() {
		server.then(MockLmStudio.error(400, "Context length exceeded: 9000 > 4096"));
		LLMException e = assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken()));
		assertEquals(LLMException.Kind.CONTEXT_TOO_LARGE, e.getKind());

		server.then(MockLmStudio.error(404, "Model 'x' not found"));
		e = assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken()));
		assertEquals(LLMException.Kind.NO_MODEL, e.getKind());

		server.then(MockLmStudio.error(500, "boom"));
		e = assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken()));
		assertEquals(LLMException.Kind.SERVER_ERROR, e.getKind());
	}

	@Test
	void invalidResponseBodies() {
		server.then(new MockLmStudio.Reply(200, "not json", 0));
		assertEquals(LLMException.Kind.INVALID_RESPONSE,
			assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken())).getKind());
		server.then(new MockLmStudio.Reply(200, "{\"choices\":[]}", 0));
		assertEquals(LLMException.Kind.INVALID_RESPONSE,
			assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken())).getKind());
	}

	@Test
	void timesOut() {
		settings.requestTimeoutSeconds = 5;
		server.then(new MockLmStudio.Reply(200, "{}", 8000));
		settings.requestTimeoutSeconds = 5;
		Settings fast = settings;
		fast.requestTimeoutSeconds = 5;
		// HttpClient timeout fires at 5s
		LLMException e = assertThrows(LLMException.class, () -> provider.chat(simple(), new CancellationToken()));
		assertEquals(LLMException.Kind.TIMEOUT, e.getKind());
	}

	@Test
	void unreachableServer() {
		settings.endpoint = "http://127.0.0.1:1/v1";
		LLMException e = assertThrows(LLMException.class, () -> provider.listModels());
		assertEquals(LLMException.Kind.UNREACHABLE, e.getKind());
		assertTrue(e.getMessage().contains("Start LM Studio"));
	}

	@Test
	void blocksNonLoopbackEndpointByDefault() {
		settings.endpoint = "http://example.com/v1";
		LLMException e = assertThrows(LLMException.class, () -> provider.listModels());
		assertEquals(LLMException.Kind.ENDPOINT_NOT_LOCAL, e.getKind());
		settings.endpoint = "http://192.168.1.50:1234/v1";
		assertEquals(LLMException.Kind.ENDPOINT_NOT_LOCAL,
			assertThrows(LLMException.class, () -> provider.listModels()).getKind());
	}

	@Test
	void cancellationAbortsPromptly() throws Exception {
		server.then(new MockLmStudio.Reply(200, "{}", 4000));
		CancellationToken tok = new CancellationToken();
		AtomicReference<LLMException> err = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				provider.chat(simple(), tok);
			}
			catch (LLMException e) {
				err.set(e);
			}
		});
		long t0 = System.currentTimeMillis();
		t.start();
		Thread.sleep(300);
		tok.cancel();
		t.join(3000);
		assertFalse(t.isAlive());
		assertTrue(System.currentTimeMillis() - t0 < 3500);
		assertEquals(LLMException.Kind.CANCELLED, err.get().getKind());
	}

	@Test
	void connectionTestReportsThreeSteps() {
		server.then(MockLmStudio.chatText("ok"));
		ConnectionReport r = provider.testConnection("test-model");
		assertTrue(r.allOk(), r.toString());
		assertEquals(3, r.steps().size());

		ConnectionReport missing = provider.testConnection("other-model");
		assertFalse(missing.allOk());
		assertEquals(2, missing.steps().size());
		assertTrue(missing.toString().contains("not loaded"));

		settings.endpoint = "http://127.0.0.1:1/v1";
		ConnectionReport down = provider.testConnection("");
		assertFalse(down.reachable());
	}
}

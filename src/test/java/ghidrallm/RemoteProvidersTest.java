package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.*;

import ghidrallm.agent.*;
import ghidrallm.config.SecretStore;
import ghidrallm.config.Settings;
import ghidrallm.llm.*;
import ghidrallm.log.DebugLog;
import ghidrallm.testutil.MockLmStudio;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;

/** OpenAI-compatible and Anthropic providers, consent policy, secrets, routing — all against a local mock server. */
class RemoteProvidersTest {
	@TempDir
	Path tmp;
	MockLmStudio server;
	Settings settings;
	String key = "sk-test-123";

	@BeforeEach
	void setUp() throws Exception {
		server = new MockLmStudio();
		settings = new Settings();
		settings.endpoint = server.endpoint();
		settings.requestTimeoutSeconds = 10;
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private ChatRequest req(String model) {
		return new ChatRequest(model, List.of(ChatMessage.system("sys"), ChatMessage.user("hi")), null, 0.2, 256);
	}

	// ------------------------------------------------------------------ consent policy

	@Test
	void remoteHostsNeedExplicitConsent() throws Exception {
		Settings s = new Settings();
		s.endpoint = "https://api.openai.com/v1";
		var uri = java.net.URI.create(s.endpoint + "/models");
		assertEquals(LLMException.Kind.ENDPOINT_NOT_LOCAL, assertThrows(LLMException.class, () -> EndpointPolicy.check(s, uri)).getKind());
		s.remoteConsentHosts.add("API.openai.com");
		EndpointPolicy.check(s, uri);   // consent is per host, case-insensitive
		assertThrows(LLMException.class, () -> EndpointPolicy.check(s, java.net.URI.create("https://api.anthropic.com/v1/models")));
		assertTrue(EndpointPolicy.isLoopbackUrl("http://localhost:1234/v1"));
		assertTrue(EndpointPolicy.isLoopbackUrl("http://127.0.0.1:1/v1"));
		assertTrue(EndpointPolicy.isLoopbackUrl("http://[::1]:1/v1"));
		assertFalse(EndpointPolicy.isLoopbackUrl("http://localhost.evil.com/v1"));
		assertFalse(EndpointPolicy.isLoopbackUrl("http://10.0.0.5/v1"));
		assertTrue(new Settings() {{ endpoint = "https://api.openai.com/v1"; }}.isRemote());
	}

	@Test
	void unapprovedRemoteProvidersNeverSendAnything() {
		Settings s = new Settings();
		s.providerType = "ANTHROPIC";
		s.endpoint = "https://api.anthropic.com/v1";
		AnthropicProvider p = new AnthropicProvider(() -> s, st -> "k");
		LLMException e = assertThrows(LLMException.class, () -> p.chat(req("claude-sonnet-5-5"), new CancellationToken()));
		assertEquals(LLMException.Kind.ENDPOINT_NOT_LOCAL, e.getKind());
		OpenAiCompatibleProvider o = new OpenAiCompatibleProvider("o", () -> {
			s.endpoint = "https://api.openai.com/v1";
			return s;
		}, st -> "k");
		assertEquals(LLMException.Kind.ENDPOINT_NOT_LOCAL, assertThrows(LLMException.class, () -> o.chat(req("gpt"), new CancellationToken())).getKind());
	}

	// ------------------------------------------------------------------ OpenAI-compatible

	@Test
	void openAiCompatibleSendsBearerKeyWhenPresent() throws Exception {
		OpenAiCompatibleProvider p = new OpenAiCompatibleProvider("openai-compatible", () -> settings, st -> key);
		server.then(MockLmStudio.chatText("ok"));
		p.chat(req("m"), new CancellationToken());
		assertEquals("Bearer " + key, server.chatHeaders.get(0).get("authorization"));
		OpenAiCompatibleProvider noKey = new OpenAiCompatibleProvider("openai-compatible", () -> settings, st -> null);
		server.then(MockLmStudio.chatText("ok"));
		noKey.chat(req("m"), new CancellationToken());
		assertNull(server.chatHeaders.get(1).get("authorization"));
	}

	@Test
	void openAiAdaptsToMaxCompletionTokensAndTemperatureRejections() throws Exception {
		OpenAiCompatibleProvider p = new OpenAiCompatibleProvider("openai-compatible", () -> settings, st -> key);
		server.then(MockLmStudio.error(400, "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."))
				.then(MockLmStudio.error(400, "Unsupported value: 'temperature' does not support 0.2 with this model."))
				.then(MockLmStudio.chatText("fine"));
		ChatResponse r = p.chat(req("o-model"), new CancellationToken());
		assertEquals("fine", r.content());
		JsonObject last = server.requests.get(2);
		assertTrue(last.has("max_completion_tokens"));
		assertFalse(last.has("max_tokens"));
		assertFalse(last.has("temperature"));
		// learned for next calls
		server.then(MockLmStudio.chatText("again"));
		p.chat(req("o-model"), new CancellationToken());
		assertTrue(server.requests.get(3).has("max_completion_tokens"));
		assertFalse(server.requests.get(3).has("temperature"));
	}

	@Test
	void authAndRateLimitErrorsAreMapped() {
		OpenAiCompatibleProvider p = new OpenAiCompatibleProvider("openai-compatible", () -> settings, st -> key);
		server.then(MockLmStudio.error(401, "Incorrect API key provided"));
		assertEquals(LLMException.Kind.AUTH_FAILED, assertThrows(LLMException.class, () -> p.chat(req("m"), new CancellationToken())).getKind());
		server.then(MockLmStudio.error(429, "Rate limit reached"));
		assertEquals(LLMException.Kind.RATE_LIMITED, assertThrows(LLMException.class, () -> p.chat(req("m"), new CancellationToken())).getKind());
	}

	// ------------------------------------------------------------------ Anthropic

	private AnthropicProvider anthropic() {
		settings.providerType = "ANTHROPIC";
		return new AnthropicProvider(() -> settings, st -> key);
	}

	@Test
	void anthropicHeadersEndpointsAndModels() throws Exception {
		AnthropicProvider p = anthropic();
		server.models = List.of("claude-sonnet-5-5", "claude-haiku-5-5");
		assertEquals(List.of("claude-sonnet-5-5", "claude-haiku-5-5"), p.listModels());
		assertEquals(key, server.modelHeaders.get(0).get("x-api-key"));
		assertEquals("2023-06-01", server.modelHeaders.get(0).get("anthropic-version"));
		server.thenMessage(MockLmStudio.anthropicText("hello"));
		ChatResponse r = p.chat(req("claude-sonnet-5-5"), new CancellationToken());
		assertEquals("hello", r.content());
		assertEquals("stop", r.finishReason());
		assertEquals(120, r.usage().totalTokens());
		assertEquals(key, server.messageHeaders.get(0).get("x-api-key"));
		assertNull(server.messageHeaders.get(0).get("authorization"), "Anthropic uses x-api-key, not bearer");
	}

	@Test
	void anthropicRequestMapping() throws Exception {
		AnthropicProvider p = anthropic();
		ToolSpec spec = new ToolSpec("get_callers", "Callers.", JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"function\":{\"type\":\"string\"}},\"required\":[\"function\"]}").getAsJsonObject());
		server.thenMessage(MockLmStudio.anthropicText("ok"));
		p.chat(new ChatRequest("claude-sonnet-5-5", List.of(ChatMessage.system("S1"), ChatMessage.system("S2"), ChatMessage.user("q"),
			ChatMessage.assistantWithCalls("", List.of(new ToolCall("tu_1", "get_callers", "{\"function\":\"main\"}"))),
			ChatMessage.tool("tu_1", "get_callers", "result text"), ChatMessage.user("Limits reached, answer now.")), List.of(spec), 0.2, 500), new CancellationToken());
		JsonObject body = server.messageRequests.get(0);
		assertEquals("S1\n\nS2", body.get("system").getAsString());
		assertEquals(8000, body.get("max_tokens").getAsInt(), "never starve reasoning models");
		assertFalse(body.has("temperature"), "gen-5 models reject sampling parameters");
		assertEquals("get_callers", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
		assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
		JsonArray msgs = body.getAsJsonArray("messages");
		assertEquals(3, msgs.size(), msgs.toString());
		assertEquals("user", msgs.get(0).getAsJsonObject().get("role").getAsString());
		JsonObject asst = msgs.get(1).getAsJsonObject();
		assertEquals("assistant", asst.get("role").getAsString());
		JsonObject use = asst.getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("tool_use", use.get("type").getAsString());
		assertEquals("main", use.getAsJsonObject("input").get("function").getAsString());
		JsonArray last = msgs.get(2).getAsJsonObject().getAsJsonArray("content");
		assertEquals("tool_result", last.get(0).getAsJsonObject().get("type").getAsString(), "tool_result first");
		assertEquals("tu_1", last.get(0).getAsJsonObject().get("tool_use_id").getAsString());
		assertEquals("text", last.get(1).getAsJsonObject().get("type").getAsString());
	}

	@Test
	void temperatureOnlyForModelsThatAcceptIt() throws Exception {
		AnthropicProvider p = anthropic();
		server.thenMessage(MockLmStudio.anthropicText("a")).thenMessage(MockLmStudio.anthropicText("b"));
		p.chat(req("claude-sonnet-4-6"), new CancellationToken());
		assertEquals(0.2, server.messageRequests.get(0).get("temperature").getAsDouble(), 1e-9);
		p.chat(req("claude-opus-4-8"), new CancellationToken());
		assertFalse(server.messageRequests.get(1).has("temperature"));
		// unknown model that rejects it -> dropped and remembered
		server.thenMessage(MockLmStudio.anthropicError(400, "invalid_request_error", "`temperature` may not be set for this model"))
				.thenMessage(MockLmStudio.anthropicText("c"));
		assertEquals("c", p.chat(req("claude-future-x"), new CancellationToken()).content());
		assertFalse(server.messageRequests.get(3).has("temperature"));
	}

	@Test
	void thinkingBlocksEchoedVerbatimOnlyInCurrentTurn() throws Exception {
		AnthropicProvider p = anthropic();
		// response with a thinking block -> providerBlocks keeps it
		server.thenMessage(MockLmStudio.anthropicToolUse("tu_9", "get_callers", "{\"function\":\"A\"}", true));
		ChatResponse r = p.chat(req("claude-sonnet-5-5"), new CancellationToken());
		assertEquals(1, r.toolCalls().size());
		assertEquals("tool_calls", r.finishReason());
		assertTrue(r.providerBlocks().contains("\"thinking\""));
		ChatMessage asst = ChatMessage.assistantWithCalls(r.content(), r.toolCalls()).withProviderBlocks(r.providerBlocks());

		// current turn: thinking block must be echoed back unchanged
		server.thenMessage(MockLmStudio.anthropicText("done"));
		p.chat(new ChatRequest("claude-sonnet-5-5", List.of(ChatMessage.user("q"), asst, ChatMessage.tool("tu_9", "get_callers", "res")), null, 0.2, 100), new CancellationToken());
		JsonArray cur = server.messageRequests.get(1).getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
		assertEquals("thinking", cur.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("sig-tu_9", cur.get(0).getAsJsonObject().get("signature").getAsString());

		// a later turn: older turns are rebuilt without thinking blocks
		server.thenMessage(MockLmStudio.anthropicText("done2"));
		p.chat(new ChatRequest("claude-sonnet-5-5", List.of(ChatMessage.user("q"), asst, ChatMessage.tool("tu_9", "get_callers", "res"),
			ChatMessage.assistant("answer"), ChatMessage.user("follow-up")), null, 0.2, 100), new CancellationToken());
		String old = server.messageRequests.get(2).getAsJsonArray("messages").get(1).toString();
		assertFalse(old.contains("thinking"), old);
		assertTrue(old.contains("tool_use"));
	}

	@Test
	void anthropicRefusalAndMaxTokensAreSurfaced() throws Exception {
		AnthropicProvider p = anthropic();
		server.thenMessage(MockLmStudio.anthropicRefusal("cyber"));
		ChatResponse r = p.chat(req("claude-sonnet-5-5"), new CancellationToken());
		assertEquals("refusal", r.finishReason());
		assertTrue(r.content().contains("declined") && r.content().contains("cyber"), r.content());
	}

	@Test
	void anthropicErrorMappingAndRetries() {
		AnthropicProvider p = anthropic();
		server.thenMessage(MockLmStudio.anthropicError(401, "authentication_error", "invalid x-api-key"));
		assertEquals(LLMException.Kind.AUTH_FAILED, assertThrows(LLMException.class, () -> p.chat(req("m"), new CancellationToken())).getKind());
		server.thenMessage(MockLmStudio.anthropicError(404, "not_found_error", "model: nope"));
		assertEquals(LLMException.Kind.NO_MODEL, assertThrows(LLMException.class, () -> p.chat(req("nope"), new CancellationToken())).getKind());
		server.thenMessage(MockLmStudio.anthropicError(400, "invalid_request_error", "prompt is too long: 250000 tokens > 200000 maximum"));
		assertEquals(LLMException.Kind.CONTEXT_TOO_LARGE, assertThrows(LLMException.class, () -> p.chat(req("m"), new CancellationToken())).getKind());
		// overloaded twice, then success: retried transparently
		int before = server.messageRequests.size();
		server.thenMessage(MockLmStudio.anthropicError(529, "overloaded_error", "Overloaded"))
				.thenMessage(MockLmStudio.anthropicError(429, "rate_limit_error", "slow down"))
				.thenMessage(MockLmStudio.anthropicText("recovered"));
		assertDoesNotThrow(() -> assertEquals("recovered", p.chat(req("m"), new CancellationToken()).content()));
		assertEquals(before + 3, server.messageRequests.size());
	}

	@Test
	void missingKeyIsExplained() {
		settings.providerType = "ANTHROPIC";
		AnthropicProvider p = new AnthropicProvider(() -> settings, st -> null);
		assertEquals(LLMException.Kind.NO_API_KEY, assertThrows(LLMException.class, () -> p.chat(req("m"), new CancellationToken())).getKind());
		assertTrue(LLMException.Kind.NO_API_KEY.help.contains("API key"));
	}

	@Test
	void anthropicConnectionTest() {
		AnthropicProvider p = anthropic();
		server.models = List.of("claude-sonnet-5-5");
		server.thenMessage(MockLmStudio.anthropicText("ok"));
		assertTrue(p.testConnection("claude-sonnet-5-5").allOk());
		ConnectionReport none = p.testConnection("");
		assertFalse(none.allOk());
		assertTrue(none.toString().contains("Choose a model"));
	}

	// ------------------------------------------------------------------ agent loop over Anthropic

	@Test
	void agentLoopDrivesToolsThroughAnthropic() {
		settings.providerType = "ANTHROPIC";
		settings.model = "claude-sonnet-5-5";
		AnthropicProvider p = new AnthropicProvider(() -> settings, st -> key);
		ToolRegistry reg = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		reg.register(new SimpleTool("get_callers", ToolPermission.READ_PROGRAM, "Callers.", List.of(ToolParam.string("function", "f", true)),
			(c, a) -> ToolResult.ok("callers of " + a.str("function") + ": main")));
		AgentLoop loop = new AgentLoop(p, reg, new DebugLog());
		server.thenMessage(MockLmStudio.anthropicToolUse("tu_1", "get_callers", "{\"function\":\"X\"}", true))
				.thenMessage(MockLmStudio.anthropicText("X is called by main."));
		Conversation conv = new Conversation();
		CancellationToken tok = new CancellationToken();
		ToolContext tc = new ToolContext(() -> null, () -> null, null, null, null, settings, new DebugLog(), tok);
		AgentResult r = loop.run(new AgentRequest(conv, "q", "", "Who calls X?", List.of(), "", true, settings, tc, tok, AgentListener.NONE));
		assertEquals(AgentResult.Status.COMPLETED, r.status(), r.error());
		assertEquals(1, r.toolCalls());
		assertEquals("X is called by main.", r.answer());
		JsonArray second = server.messageRequests.get(1).getAsJsonArray("messages");
		assertEquals("thinking", second.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("tool_result", second.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		// a rejected request must NOT silently flip Claude into the prompted protocol
		server.thenMessage(MockLmStudio.anthropicError(400, "invalid_request_error", "something else is wrong"));
		AgentResult bad = loop.run(new AgentRequest(new Conversation(), "q", "", "again", List.of(), "", true, settings, tc, tok, AgentListener.NONE));
		assertEquals(AgentResult.Status.ERROR, bad.status());
		assertFalse(loop.isPrompted(settings));
	}

	@Test
	void remoteProvidersRequireAnExplicitModel() {
		settings.providerType = "ANTHROPIC";
		settings.model = "";
		AnthropicProvider p = new AnthropicProvider(() -> settings, st -> key);
		AgentLoop loop = new AgentLoop(p, new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM)), new DebugLog());
		CancellationToken tok = new CancellationToken();
		ToolContext tc = new ToolContext(() -> null, () -> null, null, null, null, settings, new DebugLog(), tok);
		AgentResult r = loop.run(new AgentRequest(new Conversation(), "q", "", "hi", List.of(), "", true, settings, tc, tok, AgentListener.NONE));
		assertEquals(AgentResult.Status.ERROR, r.status());
		assertTrue(r.error().toLowerCase().contains("model"));
		assertTrue(server.messageRequests.isEmpty());
	}

	// ------------------------------------------------------------------ routing & secrets

	@Test
	void routingFollowsSettingsAtCallTime() throws Exception {
		Map<String, LLMProvider> m = new LinkedHashMap<>();
		m.put("LMSTUDIO", new LMStudioProvider(() -> settings));
		m.put("ANTHROPIC", new AnthropicProvider(() -> settings, st -> key));
		RoutingProvider r = new RoutingProvider(() -> settings, m);
		assertEquals("lmstudio", r.id());
		server.then(MockLmStudio.chatText("local"));
		assertEquals("local", r.chat(req("m"), new CancellationToken()).content());
		settings.providerType = "ANTHROPIC";
		assertEquals("anthropic", r.id());
		assertTrue(r.requiresExplicitModel());
		server.thenMessage(MockLmStudio.anthropicText("claude"));
		assertEquals("claude", r.chat(req("claude-sonnet-5-5"), new CancellationToken()).content());
	}

	@Test
	void secretStorePrefersEnvAndKeepsKeysOutOfSettings() throws Exception {
		Map<String, String> env = new HashMap<>();
		SecretStore s = new SecretStore(tmp, env::get);
		assertNull(s.apiKey("ANTHROPIC", "MY_KEY"));
		s.setApiKey("ANTHROPIC", "  stored-key ");
		assertEquals("stored-key", s.apiKey("ANTHROPIC", ""));
		env.put("MY_KEY", "env-key");
		assertEquals("env-key", s.apiKey("ANTHROPIC", "MY_KEY"), "env var wins");
		assertEquals("stored-key", s.apiKey("ANTHROPIC", "UNSET_VAR"));
		assertNull(s.apiKey("OPENAI_COMPATIBLE", ""), "keys are per provider");
		assertEquals("stored-key", new SecretStore(tmp, env::get).apiKey("ANTHROPIC", ""), "persisted");
		String t1 = s.mcpToken();
		assertEquals(64, t1.length());
		assertEquals(t1, new SecretStore(tmp, env::get).mcpToken());
		assertNotEquals(t1, s.regenerateMcpToken());
		// settings.json never contains secrets
		new ghidrallm.config.SettingsStore(tmp).save(new Settings());
		assertFalse(java.nio.file.Files.readString(tmp.resolve("settings.json")).contains("stored-key"));
		try {
			var perms = java.nio.file.Files.getPosixFilePermissions(tmp.resolve("secrets.json"));
			assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), perms);
		}
		catch (UnsupportedOperationException e) {
			// non-POSIX FS
		}
	}
}

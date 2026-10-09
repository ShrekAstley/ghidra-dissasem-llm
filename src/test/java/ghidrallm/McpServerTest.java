package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.junit.jupiter.api.*;

import com.google.gson.*;

import ghidra.program.model.listing.Program;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.log.DebugLog;
import ghidrallm.mcp.McpHttpServer;
import ghidrallm.mcp.McpProtocol;
import ghidrallm.testutil.TestPrograms;
import ghidrallm.tools.*;

/** MCP server against a real headless Ghidra program, over real HTTP. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerTest {
	static final String TOKEN = "t".repeat(64);
	Program program;
	DecompilerService decomp;
	ProposalManager proposals;
	KnowledgeStore knowledge;
	Settings settings = new Settings();
	DebugLog log = new DebugLog();
	McpProtocol protocol;
	McpHttpServer server;
	boolean allowProposals = false, allowKnowledge = false;
	HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();

	@BeforeAll
	void setUp() throws Exception {
		program = TestPrograms.build(this);
		decomp = new DecompilerService(30);
		proposals = new ProposalManager(() -> program, decomp);
		knowledge = KnowledgeStore.inMemory();
		protocol = new McpProtocol(() -> Tools.create(allowProposals, allowKnowledge),
			tok -> new ToolContext(() -> program, () -> program.getAddressFactory().getDefaultAddressSpace().getAddress(0x401004),
				decomp, proposals, knowledge, settings, log, tok),
			() -> settings, log);
		server = new McpHttpServer(0, () -> TOKEN, protocol, log);
	}

	@AfterAll
	void tearDown() {
		server.close();
		protocol.close();
		decomp.close();
		knowledge.close();
		program.release(this);
	}

	String url() {
		return "http://127.0.0.1:" + server.port() + "/mcp";
	}

	HttpResponse<String> post(String body, String token, Map<String, String> extra) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url())).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		extra.forEach(b::header);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	JsonObject rpc(String method, String paramsJson) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
		HttpResponse<String> r = post(body, TOKEN, Map.of());
		assertEquals(200, r.statusCode(), r.body());
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	@Test
	void initializeNegotiatesVersionAndAdvertisesTools() throws Exception {
		JsonObject r = rpc("initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
		assertEquals(7, r.get("id").getAsInt());
		JsonObject res = r.getAsJsonObject("result");
		assertEquals("2025-03-26", res.get("protocolVersion").getAsString());
		assertTrue(res.getAsJsonObject("capabilities").has("tools"));
		assertEquals("ghidra-local-llm", res.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(res.get("instructions").getAsString().contains("CONFIRMED"));
		// unknown client version -> server's newest
		assertEquals("2025-06-18", rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").getAsJsonObject("result").get("protocolVersion").getAsString());
		assertTrue(rpc("ping", null).getAsJsonObject("result").isJsonObject());
	}

	@Test
	void toolsListIsReadOnlyByDefaultAndFollowsPermissions() throws Exception {
		allowProposals = false;
		allowKnowledge = false;
		JsonArray tools = rpc("tools/list", null).getAsJsonObject("result").getAsJsonArray("tools");
		Set<String> names = new HashSet<>();
		for (JsonElement t : tools) {
			JsonObject o = t.getAsJsonObject();
			names.add(o.get("name").getAsString());
			assertEquals("object", o.getAsJsonObject("inputSchema").get("type").getAsString());
			assertTrue(o.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(), o.get("name") + " must be read-only");
			assertFalse(o.get("description").getAsString().isBlank());
		}
		assertTrue(names.contains("get_function_decompile") && names.contains("trace_value"));
		assertFalse(names.stream().anyMatch(n -> n.startsWith("propose_") || n.equals("save_note")));
		allowProposals = true;
		allowKnowledge = true;
		Set<String> more = new HashSet<>();
		rpc("tools/list", null).getAsJsonObject("result").getAsJsonArray("tools").forEach(t -> more.add(t.getAsJsonObject().get("name").getAsString()));
		assertTrue(more.contains("propose_rename_function") && more.contains("save_note"));
		assertTrue(more.size() > names.size());
		allowProposals = false;
		allowKnowledge = false;
	}

	@Test
	void toolsCallRunsOnTheRealProgram() throws Exception {
		JsonObject r = rpc("tools/call", "{\"name\":\"get_callers\",\"arguments\":{\"function\":\"FUN_00401000\"}}").getAsJsonObject("result");
		assertFalse(r.get("isError").getAsBoolean());
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(text.contains("FUN_00401060"), text);
		// default 'current' function comes from the cursor
		String cur = rpc("tools/call", "{\"name\":\"get_current_function\",\"arguments\":{}}").getAsJsonObject("result")
				.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(cur.contains("FUN_00401000"), cur);
		// decompiler through MCP
		String dec = rpc("tools/call", "{\"name\":\"get_function_decompile\",\"arguments\":{\"function\":\"FUN_00401000\"}}").getAsJsonObject("result")
				.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(dec.contains("FUN_00401040"));
	}

	@Test
	void toolErrorsAreResultsAndUnknownToolsAreProtocolErrors() throws Exception {
		JsonObject bad = rpc("tools/call", "{\"name\":\"get_function\",\"arguments\":{\"function\":\"nope\"}}").getAsJsonObject("result");
		assertTrue(bad.get("isError").getAsBoolean());
		assertTrue(bad.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("Function not found"));
		JsonObject badArgs = rpc("tools/call", "{\"name\":\"get_callers\",\"arguments\":{\"bogus\":1}}").getAsJsonObject("result");
		assertTrue(badArgs.get("isError").getAsBoolean());
		JsonObject unknown = rpc("tools/call", "{\"name\":\"rm_rf\",\"arguments\":{}}");
		assertEquals(-32602, unknown.getAsJsonObject("error").get("code").getAsInt());
		assertEquals(-32601, rpc("resources/list", null).getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void proposeToolsOnlyQueueAndOnlyWhenEnabled() throws Exception {
		String call = "{\"name\":\"propose_rename_function\",\"arguments\":{\"function\":\"FUN_00401040\",\"new_name\":\"path_plus_flags\",\"reason\":\"returns path + flags\"}}";
		allowProposals = false;
		assertEquals(-32602, rpc("tools/call", call).getAsJsonObject("error").get("code").getAsInt(), "hidden tool cannot be called");
		assertTrue(proposals.pending().isEmpty());
		allowProposals = true;
		long mod = program.getModificationNumber();
		JsonObject r = rpc("tools/call", call).getAsJsonObject("result");
		assertFalse(r.get("isError").getAsBoolean());
		assertEquals(1, proposals.pending().size());
		assertEquals(mod, program.getModificationNumber(), "MCP client cannot modify the program");
		assertEquals("FUN_00401040", program.getFunctionManager().getFunctionAt(
			program.getAddressFactory().getDefaultAddressSpace().getAddress(0x401040)).getName());
		allowProposals = false;
	}

	@Test
	void authenticationAndOriginChecks() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
		assertEquals(401, post(body, null, Map.of()).statusCode());
		assertEquals(401, post(body, "wrong-token", Map.of()).statusCode());
		assertEquals(401, post(body, TOKEN.substring(1), Map.of()).statusCode());
		assertEquals(401, post(body, TOKEN + "x", Map.of()).statusCode());
		assertEquals(200, post(body, TOKEN, Map.of()).statusCode());
		assertEquals(403, post(body, TOKEN, Map.of("Origin", "https://evil.example")).statusCode());
		assertEquals(403, post(body, TOKEN, Map.of("Origin", "http://localhost.evil.com")).statusCode());
		assertEquals(200, post(body, TOKEN, Map.of("Origin", "http://localhost:3000")).statusCode());
		assertTrue(log.snapshot().stream().anyMatch(e -> e.summary().contains("invalid bearer token")));
	}

	@Test
	void hostHeaderMustBeLoopback_dnsRebindingProtection() throws Exception {
		try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.port())) {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
			String req = "POST /mcp HTTP/1.1\r\nHost: attacker.example:" + server.port() + "\r\nAuthorization: Bearer " + TOKEN +
				"\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
			s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
			String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(resp.startsWith("HTTP/1.1 403"), resp);
		}
	}

	@Test
	void protocolEdgeCases() throws Exception {
		assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN, Map.of()).statusCode());
		assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}", TOKEN, Map.of()).statusCode(), "client response");
		HttpResponse<String> parse = post("{not json", TOKEN, Map.of());
		assertEquals(-32700, JsonParser.parseString(parse.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
		HttpResponse<String> inv = post("[1]", TOKEN, Map.of());
		assertTrue(inv.body().contains("-32600"));
		HttpResponse<String> batch = post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]", TOKEN, Map.of());
		assertEquals(2, JsonParser.parseString(batch.body()).getAsJsonArray().size());
		HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(url())).header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(405, get.statusCode());
		HttpResponse<String> other = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/other")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(404, other.statusCode());
		HttpResponse<String> big = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\"" + "a".repeat(1_100_000) + "\"}}", TOKEN, Map.of());
		assertEquals(413, big.statusCode());
	}

	@Test
	void listensOnLoopbackOnly() throws Exception {
		// A connection via a non-loopback local address must not be possible.
		for (var ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			for (var ia : Collections.list(ni.getInetAddresses())) {
				if (!ia.isLoopbackAddress() && ia instanceof Inet4Address) {
					try (Socket s = new Socket()) {
						assertThrows(IOException.class, () -> s.connect(new InetSocketAddress(ia, server.port()), 1000),
							"server reachable on " + ia);
					}
				}
			}
		}
	}

	@Test
	void toolCallsAreLoggedAndResultsMarkedSensitive() throws Exception {
		rpc("tools/call", "{\"name\":\"get_program_metadata\",\"arguments\":{}}");
		assertTrue(log.snapshot().stream().anyMatch(e -> e.category() == DebugLog.Category.TOOL_CALL && e.summary().startsWith("[MCP]")));
		assertTrue(log.snapshot().stream().anyMatch(e -> e.category() == DebugLog.Category.TOOL_RESULT && e.sensitive() && e.summary().startsWith("[MCP]")));
	}
}

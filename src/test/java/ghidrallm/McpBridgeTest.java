package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.*;

import com.google.gson.*;

import ghidrallm.config.Settings;
import ghidrallm.log.DebugLog;
import ghidrallm.mcp.McpHttpServer;
import ghidrallm.mcp.McpProtocol;
import ghidrallm.tools.*;

/** Runs examples/mcp_stdio_bridge.py as a subprocess (when python3 is available) against the real HTTP server. */
class McpBridgeTest {
	private static String python() {
		for (String c : List.of("python3", "python")) {
			try {
				Process p = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
				if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
					return c;
				}
			}
			catch (IOException | InterruptedException e) {
				// try next
			}
		}
		return null;
	}

	@Test
	void bridgeRelaysRequestsNotificationsAndErrors() throws Exception {
		String py = python();
		assumeTrue(py != null, "python not available");
		Path script = Paths.get("examples", "mcp_stdio_bridge.py").toAbsolutePath();
		assumeTrue(Files.isRegularFile(script));
		Settings s = new Settings();
		DebugLog log = new DebugLog();
		ToolRegistry reg = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		reg.register(new SimpleTool("echo_tool", ToolPermission.READ_PROGRAM, "Echo.", List.of(ToolParam.string("text", "t", true)),
			(c, a) -> ToolResult.ok("echo:" + a.str("text"))));
		McpProtocol proto = new McpProtocol(() -> reg, tok -> new ToolContext(() -> null, () -> null, null, null, null, s, log, tok), () -> s, log);
		try (McpHttpServer server = new McpHttpServer(0, () -> "tok123tok123tok123tok123tok123tok123", proto, log)) {
			ProcessBuilder pb = new ProcessBuilder(py, "-I", script.toString());
			pb.environment().put("GHIDRA_MCP_URL", "http://127.0.0.1:" + server.port() + "/mcp");
			pb.environment().put("GHIDRA_MCP_TOKEN", "tok123tok123tok123tok123tok123tok123");
			Process p = pb.start();
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n");
			w.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
			w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo_tool\",\"arguments\":{\"text\":\"hi\"}}}\n");
			w.flush();
			JsonObject a = JsonParser.parseString(r.readLine()).getAsJsonObject();
			assertEquals(1, a.get("id").getAsInt());
			assertEquals("ghidra-local-llm", a.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());
			JsonObject b = JsonParser.parseString(r.readLine()).getAsJsonObject();   // notification produced no line
			assertEquals(2, b.get("id").getAsInt());
			assertEquals("echo:hi", b.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
			w.close();
			assertTrue(p.waitFor(10, TimeUnit.SECONDS));
			assertEquals(0, p.exitValue());
		}
		// server gone -> JSON-RPC error instead of a hang or crash
		ProcessBuilder pb = new ProcessBuilder(py, "-I", script.toString());
		pb.environment().put("GHIDRA_MCP_URL", "http://127.0.0.1:1/mcp");
		pb.environment().put("GHIDRA_MCP_TOKEN", "x");
		Process p = pb.start();
		p.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}\n".getBytes());
		p.getOutputStream().close();
		String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
		assertTrue(line.contains("\"id\": 9") && line.contains("Cannot reach Ghidra"), line);
		p.waitFor(10, TimeUnit.SECONDS);
		// refuses remote URLs and missing tokens
		ProcessBuilder rem = new ProcessBuilder(py, "-I", script.toString());
		rem.environment().put("GHIDRA_MCP_URL", "http://example.com/mcp");
		rem.environment().put("GHIDRA_MCP_TOKEN", "x");
		Process rp = rem.start();
		rp.getOutputStream().close();
		assertTrue(rp.waitFor(10, TimeUnit.SECONDS));
		assertEquals(2, rp.exitValue());
	}
}

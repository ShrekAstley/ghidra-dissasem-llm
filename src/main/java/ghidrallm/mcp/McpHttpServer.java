package ghidrallm.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ghidrallm.llm.EndpointPolicy;
import ghidrallm.log.DebugLog;

/**
 * MCP "Streamable HTTP" endpoint on {@code http://127.0.0.1:<port>/mcp}.
 * <ul>
 * <li>Bound to the loopback interface only — never reachable from the network.</li>
 * <li>Every request needs {@code Authorization: Bearer <token>} (constant-time comparison).</li>
 * <li>DNS-rebinding protection: {@code Host} must be a loopback name:port and any {@code Origin} must be loopback.</li>
 * <li>1 MB request limit; POST only (no server-initiated stream, so GET returns 405).</li>
 * </ul>
 */
public class McpHttpServer implements AutoCloseable {

	static final int MAX_BODY = 1_000_000;

	private final HttpServer server;
	private final int port;

	public McpHttpServer(int port, Supplier<String> token, McpProtocol protocol, DebugLog log) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
		this.port = server.getAddress().getPort();
		Set<String> hosts = Set.of("127.0.0.1:" + this.port, "localhost:" + this.port, "[::1]:" + this.port);
		server.createContext("/mcp", ex -> {
			try {
				handle(ex, hosts, token, protocol, log);
			}
			catch (RuntimeException e) {
				log.error("MCP HTTP handler failure", e);
				send(ex, 500, "{\"error\":\"internal\"}");
			}
			finally {
				ex.close();
			}
		});
		server.setExecutor(Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "LocalLLM-MCP-http");
			t.setDaemon(true);
			return t;
		}));
		server.start();
	}

	public int port() {
		return port;
	}

	private static void handle(HttpExchange ex, Set<String> hosts, Supplier<String> token, McpProtocol protocol, DebugLog log)
			throws IOException {
		String host = ex.getRequestHeaders().getFirst("Host");
		if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
			send(ex, 403, "{\"error\":\"forbidden host\"}");
			return;
		}
		String origin = ex.getRequestHeaders().getFirst("Origin");
		if (origin != null && !origin.isEmpty() && !originAllowed(origin)) {
			send(ex, 403, "{\"error\":\"forbidden origin\"}");
			return;
		}
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (!bearerMatches(auth, token.get())) {
			ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
			log.info("MCP request rejected: missing or invalid bearer token");
			send(ex, 401, "{\"error\":\"unauthorized\"}");
			return;
		}
		if (!"POST".equals(ex.getRequestMethod())) {
			ex.getResponseHeaders().add("Allow", "POST");
			send(ex, 405, "{\"error\":\"POST only\"}");
			return;
		}
		String declared = ex.getRequestHeaders().getFirst("Content-Length");
		if (declared != null) {
			try {
				if (Long.parseLong(declared.trim()) > MAX_BODY) {
					send(ex, 413, "{\"error\":\"request too large\"}");
					return;
				}
			}
			catch (NumberFormatException e) {
				send(ex, 400, "{\"error\":\"bad content-length\"}");
				return;
			}
		}
		String body = readLimited(ex.getRequestBody());
		if (body == null) {
			send(ex, 413, "{\"error\":\"request too large\"}");
			return;
		}
		String reply = protocol.handle(body);
		if (reply == null) {
			ex.sendResponseHeaders(202, -1);
			return;
		}
		send(ex, 200, reply);
	}

	static boolean originAllowed(String origin) {
		try {
			return EndpointPolicy.isLoopbackHost(URI.create(origin).getHost());
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	static boolean bearerMatches(String header, String token) {
		if (header == null || token == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return false;
		}
		byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
	}

	private static String readLimited(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
			if (out.size() > MAX_BODY) {
				return null;
			}
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void send(HttpExchange ex, int status, String json) throws IOException {
		byte[] b = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, b.length);
		ex.getResponseBody().write(b);
	}

	@Override
	public void close() {
		server.stop(0);
	}
}

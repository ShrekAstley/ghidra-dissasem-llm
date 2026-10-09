package ghidrallm.mcp;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.*;

import ghidrallm.config.Settings;
import ghidrallm.llm.ToolSpec;
import ghidrallm.log.DebugLog;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/**
 * Model Context Protocol (JSON-RPC 2.0) server logic exposing Ghidra tools to external MCP clients.
 * Transport-independent: {@link #handle(String)} takes one JSON message (or batch) and returns the reply text, or
 * {@code null} when no reply is due (notifications / client responses). Implements: initialize, ping,
 * tools/list, tools/call. Tool permissions come from the registry supplied by the caller.
 */
public class McpProtocol {

	public static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
	public static final String SERVER_NAME = "ghidra-local-llm";
	public static final String SERVER_VERSION = "1.0.0";

	static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602,
			INTERNAL_ERROR = -32603;

	private static final String INSTRUCTIONS = "Read-only access to the reverse-engineering database of the program currently open in Ghidra. " +
		"Use the tools to fetch exactly the evidence you need (callers, strings, xrefs, decompiler output, data-flow) instead of guessing. " +
		"Decompiler output can be inaccurate: cross-check with assembly. Label claims CONFIRMED / LIKELY / POSSIBLE / UNKNOWN. " +
		"propose_* tools (when enabled) only queue suggestions that the human analyst must approve inside Ghidra; nothing is changed by calling them.";

	private final Supplier<ToolRegistry> registry;
	private final Function<CancellationToken, ToolContext> contextFactory;
	private final Supplier<Settings> settings;
	private final DebugLog log;
	private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "LocalLLM-MCP-call");
		t.setDaemon(true);
		return t;
	});

	public McpProtocol(Supplier<ToolRegistry> registry, Function<CancellationToken, ToolContext> contextFactory,
			Supplier<Settings> settings, DebugLog log) {
		this.registry = registry;
		this.contextFactory = contextFactory;
		this.settings = settings;
		this.log = log;
	}

	/** Returns the JSON reply, or null if the message needs no reply. */
	public String handle(String body) {
		JsonElement msg;
		try {
			msg = JsonParser.parseString(body);
		}
		catch (JsonParseException e) {
			return error(JsonNull.INSTANCE, PARSE_ERROR, "Parse error").toString();
		}
		if (msg.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement e : msg.getAsJsonArray()) {
				JsonObject r = handleOne(e);
				if (r != null) {
					out.add(r);
				}
			}
			return out.isEmpty() ? null : out.toString();
		}
		JsonObject r = handleOne(msg);
		return r == null ? null : r.toString();
	}

	private JsonObject handleOne(JsonElement el) {
		if (!el.isJsonObject()) {
			return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid Request");
		}
		JsonObject o = el.getAsJsonObject();
		boolean hasId = o.has("id") && !o.get("id").isJsonNull();
		JsonElement id = hasId ? o.get("id") : JsonNull.INSTANCE;
		if (!o.has("method")) {
			// a response from the client to something we never sent, or garbage
			return hasId && (o.has("result") || o.has("error")) ? null : error(id, INVALID_REQUEST, "Invalid Request");
		}
		String method = o.get("method").isJsonPrimitive() ? o.get("method").getAsString() : "";
		JsonObject params = o.has("params") && o.get("params").isJsonObject() ? o.getAsJsonObject("params") : new JsonObject();
		if (!hasId) {
			return null;   // notification (e.g. notifications/initialized, notifications/cancelled)
		}
		try {
			return switch (method) {
				case "initialize" -> result(id, initialize(params));
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> result(id, toolsList());
				case "tools/call" -> toolsCall(id, params);
				default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
			};
		}
		catch (RuntimeException e) {
			log.error("MCP request failed: " + method, e);
			return error(id, INTERNAL_ERROR, "Internal error");
		}
	}

	private JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
				? params.get("protocolVersion").getAsString()
				: "";
		String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
		JsonObject caps = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		caps.add("tools", tools);
		JsonObject info = new JsonObject();
		info.addProperty("name", SERVER_NAME);
		info.addProperty("version", SERVER_VERSION);
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", version);
		r.add("capabilities", caps);
		r.add("serverInfo", info);
		r.addProperty("instructions", INSTRUCTIONS);
		log.info("MCP client initialized (protocol " + version + (params.has("clientInfo") ? ", " + params.get("clientInfo") : "") + ")");
		return r;
	}

	private JsonObject toolsList() {
		JsonArray arr = new JsonArray();
		for (Tool t : registry.get().available()) {
			ToolDefinition d = t.definition();
			ToolSpec spec = d.toSpec(false);
			JsonObject o = new JsonObject();
			o.addProperty("name", d.name());
			o.addProperty("description", d.description());
			o.add("inputSchema", spec.parametersSchema());
			JsonObject ann = new JsonObject();
			ann.addProperty("title", d.name().replace('_', ' '));
			ann.addProperty("readOnlyHint", d.permission() == ToolPermission.READ_PROGRAM);
			ann.addProperty("destructiveHint", false);
			ann.addProperty("openWorldHint", false);
			o.add("annotations", ann);
			arr.add(o);
		}
		JsonObject r = new JsonObject();
		r.add("tools", arr);
		return r;
	}

	private JsonObject toolsCall(JsonElement id, JsonObject params) {
		String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
		if (name.isEmpty() || registry.get().find(name).isEmpty()) {
			return error(id, INVALID_PARAMS, "Unknown tool: " + name);
		}
		String args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.get("arguments").toString() : "{}";
		CancellationToken token = new CancellationToken();
		ToolRegistry reg = registry.get();
		log.log(DebugLog.Category.TOOL_CALL, "[MCP] " + name + " " + Text.oneLine(args, 200), args, false);
		long t0 = System.nanoTime();
		Future<ToolResult> f = pool.submit(() -> reg.execute(name, args, contextFactory.apply(token)));
		ToolResult tr;
		int timeout = settings.get().agentTimeoutSeconds;
		try {
			tr = f.get(timeout, TimeUnit.SECONDS);
		}
		catch (TimeoutException e) {
			token.cancel();
			f.cancel(true);
			tr = ToolResult.error("Tool call timed out after " + timeout + "s");
		}
		catch (ExecutionException e) {
			tr = ToolResult.error("Tool failed: " + e.getCause());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			tr = ToolResult.error("Interrupted");
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		String text = Text.truncate(tr.text(), settings.get().maxToolResultChars);
		log.log(DebugLog.Category.TOOL_RESULT, "[MCP] " + name + (tr.error() ? " ERROR" : " ok") + " (" + ms + " ms, " + tr.text().length() + " chars)",
			tr.text(), true);
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		JsonArray content = new JsonArray();
		content.add(block);
		JsonObject r = new JsonObject();
		r.add("content", content);
		r.addProperty("isError", tr.error());
		return result(id, r);
	}

	private static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("result", result);
		return r;
	}

	static JsonObject error(JsonElement id, int code, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("error", e);
		return r;
	}

	public void close() {
		pool.shutdownNow();
	}
}

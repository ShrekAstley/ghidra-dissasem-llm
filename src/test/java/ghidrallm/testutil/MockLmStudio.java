package ghidrallm.testutil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;

/** Minimal LM Studio stand-in: scripted /v1/chat/completions replies, /v1/models, recorded requests. */
public class MockLmStudio implements AutoCloseable {

	public record Reply(int status, String body, long delayMs) {}

	private final HttpServer server;
	private final Deque<Function<JsonObject, Reply>> script = new ArrayDeque<>();
	public final List<JsonObject> requests = new CopyOnWriteArrayList<>();
	/** Headers (lower-cased names) of each chat/completions request, parallel to {@link #requests}. */
	public final List<Map<String, String>> chatHeaders = new CopyOnWriteArrayList<>();
	/** Anthropic-style /v1/messages traffic. */
	public final List<JsonObject> messageRequests = new CopyOnWriteArrayList<>();
	public final List<Map<String, String>> messageHeaders = new CopyOnWriteArrayList<>();
	public final List<Map<String, String>> modelHeaders = new CopyOnWriteArrayList<>();
	private final Deque<Function<JsonObject, Reply>> messageScript = new ArrayDeque<>();
	public volatile List<String> models = List.of("test-model");
	private volatile Function<JsonObject, Reply> fallback = r -> chatText("(no more scripted replies)");

	public MockLmStudio() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/models", ex -> {
			modelHeaders.add(headersOf(ex));
			JsonArray data = new JsonArray();
			for (String m : models) {
				JsonObject o = new JsonObject();
				o.addProperty("id", m);
				o.addProperty("object", "model");
				data.add(o);
			}
			JsonObject root = new JsonObject();
			root.add("data", data);
			respond(ex, new Reply(200, root.toString(), 0));
		});
		server.createContext("/v1/chat/completions", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			JsonObject req = JsonParser.parseString(body).getAsJsonObject();
			requests.add(req);
			chatHeaders.add(headersOf(ex));
			Function<JsonObject, Reply> f;
			synchronized (script) {
				f = script.isEmpty() ? fallback : script.poll();
			}
			respond(ex, f.apply(req));
		});
		server.createContext("/v1/messages", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			JsonObject req = JsonParser.parseString(body).getAsJsonObject();
			messageRequests.add(req);
			messageHeaders.add(headersOf(ex));
			Function<JsonObject, Reply> f;
			synchronized (messageScript) {
				f = messageScript.isEmpty() ? r -> anthropicText("(no more scripted replies)") : messageScript.poll();
			}
			respond(ex, f.apply(req));
		});
		server.start();
	}

	private static Map<String, String> headersOf(com.sun.net.httpserver.HttpExchange ex) {
		Map<String, String> m = new HashMap<>();
		ex.getRequestHeaders().forEach((k, v) -> m.put(k.toLowerCase(), v.get(0)));
		return m;
	}

	/** Queue a reply for POST /v1/messages (Anthropic wire format). */
	public MockLmStudio thenMessage(Reply r) {
		synchronized (messageScript) {
			messageScript.add(req -> r);
		}
		return this;
	}

	public MockLmStudio thenMessage(Function<JsonObject, Reply> f) {
		synchronized (messageScript) {
			messageScript.add(f);
		}
		return this;
	}

	public static Reply anthropicText(String text) {
		return anthropic(blocks(textBlock(text)), "end_turn", 100, 20);
	}

	public static Reply anthropicToolUse(String id, String name, String inputJson, boolean withThinking) {
		JsonArray c = new JsonArray();
		if (withThinking) {
			JsonObject t = new JsonObject();
			t.addProperty("type", "thinking");
			t.addProperty("thinking", "");
			t.addProperty("signature", "sig-" + id);
			c.add(t);
		}
		c.add(textBlock("Checking."));
		JsonObject u = new JsonObject();
		u.addProperty("type", "tool_use");
		u.addProperty("id", id);
		u.addProperty("name", name);
		u.add("input", JsonParser.parseString(inputJson));
		c.add(u);
		return anthropic(c, "tool_use", 120, 30);
	}

	public static Reply anthropicRefusal(String category) {
		JsonObject root = JsonParser.parseString(anthropic(new JsonArray(), "refusal", 10, 0).body()).getAsJsonObject();
		JsonObject d = new JsonObject();
		d.addProperty("type", "refusal");
		d.addProperty("category", category);
		root.add("stop_details", d);
		return new Reply(200, root.toString(), 0);
	}

	public static Reply anthropicError(int status, String type, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		e.addProperty("message", message);
		JsonObject root = new JsonObject();
		root.addProperty("type", "error");
		root.add("error", e);
		return new Reply(status, root.toString(), 0);
	}

	private static JsonObject textBlock(String t) {
		JsonObject b = new JsonObject();
		b.addProperty("type", "text");
		b.addProperty("text", t);
		return b;
	}

	private static JsonArray blocks(JsonObject... bs) {
		JsonArray a = new JsonArray();
		for (JsonObject b : bs) {
			a.add(b);
		}
		return a;
	}

	private static Reply anthropic(JsonArray content, String stop, int in, int out) {
		JsonObject root = new JsonObject();
		root.addProperty("id", "msg_test");
		root.addProperty("type", "message");
		root.addProperty("role", "assistant");
		root.add("content", content);
		root.addProperty("stop_reason", stop);
		JsonObject u = new JsonObject();
		u.addProperty("input_tokens", in);
		u.addProperty("output_tokens", out);
		root.add("usage", u);
		return new Reply(200, root.toString(), 0);
	}

	private static void respond(com.sun.net.httpserver.HttpExchange ex, Reply r) throws IOException {
		try {
			if (r.delayMs() > 0) {
				Thread.sleep(r.delayMs());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		try {
			ex.sendResponseHeaders(r.status(), b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		}
		catch (IOException e) {
			// client went away (cancellation tests)
		}
	}

	public String endpoint() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	public MockLmStudio then(Function<JsonObject, Reply> f) {
		synchronized (script) {
			script.add(f);
		}
		return this;
	}

	public MockLmStudio then(Reply r) {
		return then(req -> r);
	}

	public MockLmStudio fallback(Function<JsonObject, Reply> f) {
		this.fallback = f;
		return this;
	}

	public int remaining() {
		synchronized (script) {
			return script.size();
		}
	}

	// ---- reply builders ----

	public static Reply chatText(String text) {
		return new Reply(200, completion(text, null, "stop"), 0);
	}

	public static Reply chatText(String text, int prompt, int completion) {
		JsonObject o = JsonParser.parseString(completion(text, null, "stop")).getAsJsonObject();
		JsonObject u = new JsonObject();
		u.addProperty("prompt_tokens", prompt);
		u.addProperty("completion_tokens", completion);
		u.addProperty("total_tokens", prompt + completion);
		o.add("usage", u);
		return new Reply(200, o.toString(), 0);
	}

	public static Reply nativeToolCall(String id, String name, String argsJson) {
		JsonArray calls = new JsonArray();
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("arguments", argsJson);
		JsonObject c = new JsonObject();
		c.addProperty("id", id);
		c.addProperty("type", "function");
		c.add("function", fn);
		calls.add(c);
		return new Reply(200, completion("", calls, "tool_calls"), 0);
	}

	public static Reply taggedToolCall(String name, String argsJson) {
		return chatText("Let me check.\n<tool_call>\n{\"name\": \"" + name + "\", \"arguments\": " + argsJson + "}\n</tool_call>");
	}

	public static Reply error(int status, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("message", message);
		JsonObject root = new JsonObject();
		root.add("error", e);
		return new Reply(status, root.toString(), 0);
	}

	private static String completion(String content, JsonArray toolCalls, String finish) {
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "assistant");
		msg.addProperty("content", content);
		if (toolCalls != null) {
			msg.add("tool_calls", toolCalls);
		}
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("message", msg);
		choice.addProperty("finish_reason", finish);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject root = new JsonObject();
		root.add("choices", choices);
		return root.toString();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}

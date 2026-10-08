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
	public volatile List<String> models = List.of("test-model");
	private volatile Function<JsonObject, Reply> fallback = r -> chatText("(no more scripted replies)");

	public MockLmStudio() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/models", ex -> {
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
			Function<JsonObject, Reply> f;
			synchronized (script) {
				f = script.isEmpty() ? fallback : script.poll();
			}
			respond(ex, f.apply(req));
		});
		server.start();
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

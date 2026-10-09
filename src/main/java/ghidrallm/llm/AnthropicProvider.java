package ghidrallm.llm;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.*;

import ghidrallm.config.Settings;
import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/**
 * Anthropic Messages API ({@code POST {endpoint}/messages}). Remote: requires explicit user consent for the host
 * and an API key. Written against the documented wire format:
 * <ul>
 * <li>auth via {@code x-api-key} + {@code anthropic-version} headers;</li>
 * <li>system prompt as the top-level {@code system} string; tool results as {@code tool_result} blocks in a user message;</li>
 * <li>thinking / tool_use blocks of the <em>current</em> turn are echoed back verbatim (they must not be edited), older turns are rebuilt without thinking;</li>
 * <li>newer models reject sampling parameters, so {@code temperature} is only sent to models that accept it (and dropped on a 400);</li>
 * <li>{@code stop_reason: "refusal"} is surfaced as a readable message.</li>
 * </ul>
 */
public class AnthropicProvider implements LLMProvider {

	static final String VERSION = "2023-06-01";
	/** Thinking models spend output tokens on reasoning; never starve the answer. */
	static final int MIN_MAX_TOKENS = 8000;

	private final Supplier<Settings> settings;
	private final Function<Settings, String> keySource;
	private final HttpTransport http = new HttpTransport();
	private volatile boolean sendTemperature = true;

	public AnthropicProvider(Supplier<Settings> settings, Function<Settings, String> keySource) {
		this.settings = settings;
		this.keySource = keySource;
	}

	@Override
	public String id() {
		return "anthropic";
	}

	@Override
	public boolean canFallBackToPrompted() {
		return false;
	}

	@Override
	public boolean requiresExplicitModel() {
		return true;
	}

	/** Models released from generation 5 on, and Opus 4.7/4.8, reject non-default sampling parameters. */
	static boolean modelAcceptsSampling(String model) {
		if (model == null) {
			return true;
		}
		String m = model.toLowerCase();
		return !(m.matches("claude-(fable|mythos|opus|sonnet|haiku)-5.*") || m.matches("claude-opus-4-[78].*"));
	}

	@Override
	public List<String> listModels() throws LLMException {
		HttpRequest req = request("/models?limit=1000").GET().timeout(Duration.ofSeconds(20)).build();
		String body = http.send(req, new CancellationToken(), 20, true, AnthropicProvider::statusError).body();
		try {
			JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
			List<String> out = new ArrayList<>();
			for (JsonElement e : data) {
				out.add(e.getAsJsonObject().get("id").getAsString());
			}
			return out;
		}
		catch (RuntimeException e) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "unexpected /models payload", 0, e);
		}
	}

	@Override
	public ChatResponse chat(ChatRequest request, CancellationToken cancel) throws LLMException {
		Settings s = settings.get();
		boolean temp = sendTemperature && modelAcceptsSampling(request.model());
		for (int adjust = 0;; adjust++) {
			String json = toRequestJson(request, temp, s.effort);
			HttpRequest req = request("/messages").header("content-type", "application/json")
					.timeout(Duration.ofSeconds(s.requestTimeoutSeconds)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
			long t0 = System.nanoTime();
			try {
				HttpResponse<String> resp = http.send(req, cancel, s.requestTimeoutSeconds, true, AnthropicProvider::statusError);
				return parseResponse(resp.body(), (System.nanoTime() - t0) / 1_000_000);
			}
			catch (LLMException e) {
				if (e.getKind() == LLMException.Kind.BAD_REQUEST && temp && adjust < 1 &&
					String.valueOf(e.getMessage()).toLowerCase().contains("temperature")) {
					temp = false;
					sendTemperature = false;
					continue;
				}
				throw e;
			}
		}
	}

	@Override
	public ConnectionReport testConnection(String model) {
		return ConnectionTest.run(this, settings.get().endpoint, model);
	}

	private HttpRequest.Builder request(String path) throws LLMException {
		Settings s = settings.get();
		URI uri;
		try {
			uri = URI.create(s.endpoint + path);
		}
		catch (IllegalArgumentException e) {
			throw new LLMException(LLMException.Kind.BAD_REQUEST, "invalid endpoint URL", 0, e);
		}
		EndpointPolicy.check(s, uri);
		String key = keySource == null ? null : keySource.apply(s);
		if (key == null || key.isBlank()) {
			throw new LLMException(LLMException.Kind.NO_API_KEY, uri.getHost());
		}
		return HttpRequest.newBuilder(uri).header("x-api-key", key).header("anthropic-version", VERSION);
	}

	// ---- request mapping -------------------------------------------------------------------

	static String toRequestJson(ChatRequest req, boolean includeTemperature, String effort) {
		JsonObject o = new JsonObject();
		o.addProperty("model", req.model());
		o.addProperty("max_tokens", Math.max(req.maxTokens(), MIN_MAX_TOKENS));
		if (includeTemperature) {
			o.addProperty("temperature", req.temperature());
		}
		if (effort != null && !effort.isBlank()) {
			JsonObject oc = new JsonObject();
			oc.addProperty("effort", effort);
			o.add("output_config", oc);
		}
		StringBuilder system = new StringBuilder();
		JsonArray messages = new JsonArray();
		List<ChatMessage> in = req.messages();
		int turnStart = currentTurnStart(in);
		for (int i = 0; i < in.size(); i++) {
			ChatMessage m = in.get(i);
			switch (m.role()) {
				case ChatMessage.SYSTEM -> {
					if (m.content() != null && !m.content().isBlank()) {
						system.append(system.length() > 0 ? "\n\n" : "").append(m.content());
					}
				}
				case ChatMessage.USER -> appendBlocks(messages, "user", textBlocks(m.content()));
				case ChatMessage.TOOL -> {
					JsonObject b = new JsonObject();
					b.addProperty("type", "tool_result");
					b.addProperty("tool_use_id", m.toolCallId());
					b.addProperty("content", m.content() == null || m.content().isEmpty() ? "(empty result)" : m.content());
					appendBlocks(messages, "user", singleton(b));
				}
				default -> appendBlocks(messages, "assistant", assistantBlocks(m, i >= turnStart));
			}
		}
		if (messages.isEmpty() || !"user".equals(messages.get(0).getAsJsonObject().get("role").getAsString())) {
			JsonObject first = new JsonObject();
			first.addProperty("role", "user");
			first.add("content", textBlocks("(conversation continues)"));
			JsonArray fixed = new JsonArray();
			fixed.add(first);
			messages.forEach(fixed::add);
			messages = fixed;
		}
		if (system.length() > 0) {
			o.addProperty("system", system.toString());
		}
		o.add("messages", messages);
		if (!req.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec t : req.tools()) {
				JsonObject tool = new JsonObject();
				tool.addProperty("name", t.name());
				tool.addProperty("description", t.description());
				tool.add("input_schema", t.parametersSchema());
				tools.add(tool);
			}
			o.add("tools", tools);
		}
		return new Gson().toJson(o);
	}

	/** Index of the first message of the current turn: the last user message that is not a tool-result follow-up. */
	private static int currentTurnStart(List<ChatMessage> msgs) {
		for (int i = msgs.size() - 1; i >= 0; i--) {
			if (ChatMessage.USER.equals(msgs.get(i).role()) && (i == 0 || !ChatMessage.TOOL.equals(msgs.get(i - 1).role()))) {
				return i;
			}
		}
		return 0;
	}

	private static JsonArray singleton(JsonObject b) {
		JsonArray a = new JsonArray();
		a.add(b);
		return a;
	}

	private static JsonArray textBlocks(String text) {
		JsonArray a = new JsonArray();
		JsonObject b = new JsonObject();
		b.addProperty("type", "text");
		b.addProperty("text", text == null || text.isBlank() ? "(empty)" : text);
		a.add(b);
		return a;
	}

	private static JsonArray assistantBlocks(ChatMessage m, boolean inCurrentTurn) {
		if (inCurrentTurn && m.providerBlocks() != null) {
			try {
				JsonArray raw = JsonParser.parseString(m.providerBlocks()).getAsJsonArray();
				if (!raw.isEmpty()) {
					return raw;   // verbatim, including thinking blocks
				}
			}
			catch (RuntimeException e) {
				// fall back to rebuilding
			}
		}
		JsonArray a = new JsonArray();
		if (m.content() != null && !m.content().isBlank()) {
			JsonObject t = new JsonObject();
			t.addProperty("type", "text");
			t.addProperty("text", m.content());
			a.add(t);
		}
		for (ToolCall c : m.toolCalls()) {
			JsonObject u = new JsonObject();
			u.addProperty("type", "tool_use");
			u.addProperty("id", c.id());
			u.addProperty("name", c.name());
			JsonElement input;
			try {
				input = JsonParser.parseString(c.argumentsJson() == null || c.argumentsJson().isBlank() ? "{}" : c.argumentsJson());
				if (!input.isJsonObject()) {
					input = new JsonObject();
				}
			}
			catch (RuntimeException e) {
				input = new JsonObject();
			}
			u.add("input", input);
			a.add(u);
		}
		if (a.isEmpty()) {
			return textBlocks("(no content)");
		}
		return a;
	}

	/** Appends blocks to the last message if it has the same role (Anthropic wants alternating roles). */
	private static void appendBlocks(JsonArray messages, String role, JsonArray blocks) {
		if (!messages.isEmpty()) {
			JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
			if (role.equals(last.get("role").getAsString())) {
				JsonArray content = last.getAsJsonArray("content");
				if ("user".equals(role)) {
					// tool_result blocks must precede text in a user message
					boolean hasText = false;
					for (JsonElement e : content) {
						hasText |= "text".equals(e.getAsJsonObject().get("type").getAsString());
					}
					for (JsonElement e : blocks) {
						boolean isResult = "tool_result".equals(e.getAsJsonObject().get("type").getAsString());
						if (isResult && hasText) {
							// should not happen; keep order valid by inserting before the first text block
							JsonArray reordered = new JsonArray();
							reordered.add(e);
							content.forEach(reordered::add);
							last.add("content", reordered);
							content = reordered;
						}
						else {
							content.add(e);
						}
					}
				}
				else {
					blocks.forEach(content::add);
				}
				return;
			}
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("role", role);
		msg.add("content", blocks);
		messages.add(msg);
	}

	// ---- response mapping ------------------------------------------------------------------

	static ChatResponse parseResponse(String body, long elapsedMillis) throws LLMException {
		JsonObject root;
		try {
			root = JsonParser.parseString(body).getAsJsonObject();
		}
		catch (RuntimeException e) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "malformed JSON", 0, e);
		}
		if ("error".equals(str(root, "type")) && root.has("error")) {
			throw OpenAiCompatibleProvider.refine(OpenAiJson.errorFrom(root.get("error"), 0), 0);
		}
		if (!root.has("content") || !root.get("content").isJsonArray()) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "no content in response");
		}
		JsonArray content = root.getAsJsonArray("content");
		StringBuilder text = new StringBuilder();
		List<ToolCall> calls = new ArrayList<>();
		for (JsonElement e : content) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject b = e.getAsJsonObject();
			String type = str(b, "type");
			if ("text".equals(type)) {
				text.append(str(b, "text"));
			}
			else if ("tool_use".equals(type)) {
				calls.add(new ToolCall(str(b, "id"), str(b, "name"), b.has("input") ? b.get("input").toString() : "{}"));
			}
		}
		String stop = str(root, "stop_reason");
		String finish = switch (stop) {
			case "end_turn", "stop_sequence" -> "stop";
			case "tool_use" -> "tool_calls";
			case "max_tokens" -> "length";
			default -> stop;
		};
		if ("refusal".equals(stop) && text.length() == 0) {
			String cat = "";
			if (root.has("stop_details") && root.get("stop_details").isJsonObject()) {
				cat = str(root.getAsJsonObject("stop_details"), "category");
			}
			text.append("[The provider declined this request").append(cat.isEmpty() ? "" : " (category: " + cat + ")")
					.append(". Try rephrasing the question or use a different model.]");
		}
		Usage usage = Usage.NONE;
		if (root.has("usage") && root.get("usage").isJsonObject()) {
			JsonObject u = root.getAsJsonObject("usage");
			int in = intOf(u, "input_tokens") + intOf(u, "cache_creation_input_tokens") + intOf(u, "cache_read_input_tokens");
			int out = intOf(u, "output_tokens");
			usage = new Usage(in, out, in + out);
		}
		return new ChatResponse(text.toString(), calls, finish, usage, elapsedMillis, body, content.toString());
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static int intOf(JsonObject o, String k) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
		}
		catch (RuntimeException e) {
			return 0;
		}
	}

	static LLMException statusError(HttpResponse<String> r) {
		String body = r.body() == null ? "" : r.body();
		int st = r.statusCode();
		String msg = "HTTP " + st + ": " + Text.truncate(body, 300);
		String type = "";
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			if (root.has("error") && root.get("error").isJsonObject()) {
				JsonObject err = root.getAsJsonObject("error");
				msg = str(err, "message").isEmpty() ? msg : str(err, "message");
				type = str(err, "type");
			}
		}
		catch (RuntimeException e) {
			// keep generic message
		}
		String l = msg.toLowerCase();
		if (st == 401 || st == 403 || "authentication_error".equals(type) || "permission_error".equals(type)) {
			return new LLMException(LLMException.Kind.AUTH_FAILED, msg, st, null);
		}
		if (st == 429 || st == 529 || "rate_limit_error".equals(type) || "overloaded_error".equals(type)) {
			return new LLMException(LLMException.Kind.RATE_LIMITED, msg, st, null);
		}
		if (st == 404 || "not_found_error".equals(type)) {
			return new LLMException(LLMException.Kind.NO_MODEL, msg, st, null);
		}
		if (st == 413 || l.contains("prompt is too long") || l.contains("context window") || l.contains("too many tokens")) {
			return new LLMException(LLMException.Kind.CONTEXT_TOO_LARGE, msg, st, null);
		}
		if (st >= 500) {
			return new LLMException(LLMException.Kind.SERVER_ERROR, msg, st, null);
		}
		return new LLMException(LLMException.Kind.BAD_REQUEST, msg, st, null);
	}
}

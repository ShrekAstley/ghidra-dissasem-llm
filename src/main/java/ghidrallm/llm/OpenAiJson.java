package ghidrallm.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;

/** (De)serialization for the OpenAI-compatible chat-completions wire format. */
public final class OpenAiJson {
	private static final Gson GSON = new Gson();

	private OpenAiJson() {}

	public static String toRequestJson(ChatRequest req) {
		JsonObject o = new JsonObject();
		if (req.model() != null && !req.model().isBlank()) {
			o.addProperty("model", req.model());
		}
		o.addProperty("temperature", req.temperature());
		o.addProperty("max_tokens", req.maxTokens());
		o.addProperty("stream", false);
		JsonArray msgs = new JsonArray();
		for (ChatMessage m : req.messages()) {
			msgs.add(messageToJson(m));
		}
		o.add("messages", msgs);
		if (!req.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec t : req.tools()) {
				JsonObject fn = new JsonObject();
				fn.addProperty("name", t.name());
				fn.addProperty("description", t.description());
				fn.add("parameters", t.parametersSchema());
				JsonObject wrap = new JsonObject();
				wrap.addProperty("type", "function");
				wrap.add("function", fn);
				tools.add(wrap);
			}
			o.add("tools", tools);
			o.addProperty("tool_choice", "auto");
		}
		return GSON.toJson(o);
	}

	static JsonObject messageToJson(ChatMessage m) {
		JsonObject o = new JsonObject();
		o.addProperty("role", m.role());
		if (ChatMessage.TOOL.equals(m.role())) {
			o.addProperty("tool_call_id", m.toolCallId());
			if (m.name() != null) {
				o.addProperty("name", m.name());
			}
			o.addProperty("content", m.content());
			return o;
		}
		if (!m.toolCalls().isEmpty()) {
			o.addProperty("content", m.content() == null ? "" : m.content());
			JsonArray calls = new JsonArray();
			for (ToolCall c : m.toolCalls()) {
				JsonObject fn = new JsonObject();
				fn.addProperty("name", c.name());
				fn.addProperty("arguments", c.argumentsJson());
				JsonObject call = new JsonObject();
				call.addProperty("id", c.id());
				call.addProperty("type", "function");
				call.add("function", fn);
				calls.add(call);
			}
			o.add("tool_calls", calls);
			return o;
		}
		o.addProperty("content", m.content() == null ? "" : m.content());
		return o;
	}

	public static ChatResponse parseResponse(String body, long elapsedMillis) throws LLMException {
		JsonObject root;
		try {
			JsonElement el = JsonParser.parseString(body);
			if (!el.isJsonObject()) {
				throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "not a JSON object");
			}
			root = el.getAsJsonObject();
		}
		catch (JsonParseException e) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "malformed JSON", 0, e);
		}
		if (root.has("error") && !root.get("error").isJsonNull()) {
			throw errorFrom(root.get("error"), 0);
		}
		JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
				? root.getAsJsonArray("choices")
				: null;
		if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "no choices in response");
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		JsonObject msg = choice.has("message") && choice.get("message").isJsonObject()
				? choice.getAsJsonObject("message")
				: new JsonObject();
		String content = msg.has("content") && !msg.get("content").isJsonNull()
				? msg.get("content").getAsString()
				: "";
		List<ToolCall> calls = new ArrayList<>();
		if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
			int n = 0;
			for (JsonElement ce : msg.getAsJsonArray("tool_calls")) {
				if (!ce.isJsonObject()) {
					continue;
				}
				JsonObject c = ce.getAsJsonObject();
				JsonObject fn = c.has("function") && c.get("function").isJsonObject()
						? c.getAsJsonObject("function")
						: null;
				if (fn == null || !fn.has("name")) {
					continue;
				}
				String id = c.has("id") && !c.get("id").isJsonNull() ? c.get("id").getAsString()
						: "call_" + (n);
				String args = "{}";
				if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
					JsonElement a = fn.get("arguments");
					args = a.isJsonPrimitive() ? a.getAsString() : a.toString();
				}
				calls.add(new ToolCall(id, fn.get("name").getAsString(), args));
				n++;
			}
		}
		String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
				? choice.get("finish_reason").getAsString()
				: "";
		Usage usage = Usage.NONE;
		if (root.has("usage") && root.get("usage").isJsonObject()) {
			JsonObject u = root.getAsJsonObject("usage");
			usage = new Usage(intOf(u, "prompt_tokens"), intOf(u, "completion_tokens"),
				intOf(u, "total_tokens"));
		}
		return new ChatResponse(content, calls, finish, usage, elapsedMillis, body);
	}

	private static int intOf(JsonObject o, String k) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
		}
		catch (RuntimeException e) {
			return 0;
		}
	}

	/** Maps an error payload / HTTP status to a categorized exception. */
	public static LLMException errorFrom(JsonElement error, int status) {
		String msg = error.isJsonObject() && error.getAsJsonObject().has("message")
				? error.getAsJsonObject().get("message").getAsString()
				: error.toString();
		return classify(msg, status);
	}

	public static LLMException classify(String msg, int status) {
		String l = msg == null ? "" : msg.toLowerCase();
		if (l.contains("context") && (l.contains("length") || l.contains("overflow") ||
			l.contains("exceed") || l.contains("too large") || l.contains("too long"))) {
			return new LLMException(LLMException.Kind.CONTEXT_TOO_LARGE, msg, status, null);
		}
		if (l.contains("no models loaded") || l.contains("model not loaded") ||
			l.contains("no model") || (l.contains("model") && l.contains("not found"))) {
			return new LLMException(LLMException.Kind.NO_MODEL, msg, status, null);
		}
		if (status >= 500) {
			return new LLMException(LLMException.Kind.SERVER_ERROR, msg, status, null);
		}
		return new LLMException(LLMException.Kind.BAD_REQUEST, msg, status, null);
	}

	public static List<String> parseModels(String body) throws LLMException {
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			List<String> out = new ArrayList<>();
			for (JsonElement e : root.getAsJsonArray("data")) {
				out.add(e.getAsJsonObject().get("id").getAsString());
			}
			return out;
		}
		catch (RuntimeException e) {
			throw new LLMException(LLMException.Kind.INVALID_RESPONSE, "unexpected /models payload", 0,
				e);
		}
	}
}

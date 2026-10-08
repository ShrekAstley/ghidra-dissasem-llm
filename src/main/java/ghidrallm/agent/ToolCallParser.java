package ghidrallm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.*;

import ghidrallm.llm.ToolCall;

/**
 * Parses the prompted (non-native) tool-call protocol:
 * <pre>&lt;tool_call&gt;{"name": "...", "arguments": {...}}&lt;/tool_call&gt;</pre>
 * The output is only data; nothing here executes anything. Names are validated against the registry
 * by the caller, and arguments are validated against each tool's schema before execution.
 */
public final class ToolCallParser {
	private ToolCallParser() {}

	public static final int MAX_CALLS_PER_MESSAGE = 8;

	public record Parsed(List<ToolCall> calls, List<String> errors, String visibleText) {}

	private static final Pattern BLOCK = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
	private static final Pattern OPEN_ONLY = Pattern.compile("<tool_call>(?!.*</tool_call>)(.*)$", Pattern.DOTALL);
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

	public static Parsed parse(String content) {
		List<ToolCall> calls = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		if (content == null || !content.contains("<tool_call>")) {
			return new Parsed(calls, errors, content == null ? "" : content);
		}
		Matcher m = BLOCK.matcher(content);
		StringBuilder visible = new StringBuilder();
		int last = 0;
		int idx = 0;
		while (m.find()) {
			visible.append(content, last, m.start());
			last = m.end();
			idx = parseBody(m.group(1), calls, errors, idx);
		}
		String rest = content.substring(last);
		Matcher o = OPEN_ONLY.matcher(rest);
		if (o.find()) {
			visible.append(rest, 0, o.start());
			// Tolerate a missing closing tag only if the body is complete JSON.
			idx = parseBody(o.group(1), calls, errors, idx);
		}
		else {
			visible.append(rest);
		}
		if (calls.size() > MAX_CALLS_PER_MESSAGE) {
			errors.add("Too many tool calls in one message (max " + MAX_CALLS_PER_MESSAGE + "); extra calls ignored.");
			calls = new ArrayList<>(calls.subList(0, MAX_CALLS_PER_MESSAGE));
		}
		return new Parsed(calls, errors, visible.toString().trim());
	}

	private static int parseBody(String body, List<ToolCall> calls, List<String> errors, int idx) {
		String b = body.trim();
		if (b.startsWith("```")) {
			b = b.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```\\s*$", "").trim();
		}
		JsonElement el;
		try {
			el = JsonParser.parseString(b);
		}
		catch (JsonParseException e) {
			errors.add("Malformed tool_call (not valid JSON): " + ghidrallm.util.Text.oneLine(b, 120));
			return idx;
		}
		List<JsonElement> items = new ArrayList<>();
		if (el.isJsonArray()) {
			el.getAsJsonArray().forEach(items::add);
		}
		else {
			items.add(el);
		}
		for (JsonElement item : items) {
			if (!item.isJsonObject()) {
				errors.add("Malformed tool_call: expected a JSON object with 'name' and 'arguments'.");
				continue;
			}
			JsonObject o = item.getAsJsonObject();
			String name = firstString(o, "name", "tool", "function", "tool_name");
			if (name == null || !NAME.matcher(name).matches()) {
				errors.add("Malformed tool_call: missing or invalid 'name'.");
				continue;
			}
			JsonElement args = first(o, "arguments", "parameters", "args", "input");
			String argsJson = "{}";
			if (args != null && !args.isJsonNull()) {
				if (args.isJsonObject()) {
					argsJson = args.toString();
				}
				else if (args.isJsonPrimitive()) {
					try {
						JsonElement inner = JsonParser.parseString(args.getAsString());
						if (!inner.isJsonObject()) {
							errors.add("Malformed tool_call '" + name + "': arguments must be a JSON object.");
							continue;
						}
						argsJson = inner.toString();
					}
					catch (JsonParseException e) {
						errors.add("Malformed tool_call '" + name + "': arguments are not valid JSON.");
						continue;
					}
				}
				else {
					errors.add("Malformed tool_call '" + name + "': arguments must be a JSON object.");
					continue;
				}
			}
			calls.add(new ToolCall("call_" + (idx++), name, argsJson));
		}
		return idx;
	}

	private static JsonElement first(JsonObject o, String... keys) {
		for (String k : keys) {
			if (o.has(k)) {
				return o.get(k);
			}
		}
		return null;
	}

	private static String firstString(JsonObject o, String... keys) {
		JsonElement e = first(o, keys);
		return e != null && e.isJsonPrimitive() ? e.getAsString().trim() : null;
	}
}

package ghidrallm.tools;

import java.util.*;

import com.google.gson.*;

import ghidrallm.llm.ToolSpec;

/**
 * The complete, explicit set of capabilities the model may invoke. Arguments are validated against
 * each tool's declared schema before execution; nothing outside this registry can ever run.
 */
public class ToolRegistry {
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final EnumSet<ToolPermission> granted;

	public ToolRegistry(Set<ToolPermission> granted) {
		this.granted = granted.isEmpty() ? EnumSet.noneOf(ToolPermission.class) : EnumSet.copyOf(granted);
	}

	public void register(Tool t) {
		String n = t.definition().name();
		if (!n.matches("[a-z][a-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid tool name: " + n);
		}
		if (tools.putIfAbsent(n, t) != null) {
			throw new IllegalArgumentException("Duplicate tool: " + n);
		}
	}

	public boolean isGranted(Tool t) {
		return granted.contains(t.definition().permission());
	}

	public Optional<Tool> find(String name) {
		Tool t = tools.get(name);
		return t != null && isGranted(t) ? Optional.of(t) : Optional.empty();
	}

	/** Tools the model is allowed to see/call. */
	public List<Tool> available() {
		List<Tool> out = new ArrayList<>();
		for (Tool t : tools.values()) {
			if (isGranted(t)) {
				out.add(t);
			}
		}
		return out;
	}

	public List<ToolSpec> specs() {
		return specs(false);
	}

	public List<ToolSpec> specs(boolean compact) {
		return available().stream().map(t -> t.definition().toSpec(compact)).toList();
	}

	/** Parses and validates a raw-JSON argument string, then executes. Never throws. */
	public ToolResult execute(String name, String argumentsJson, ToolContext ctx) {
		Optional<Tool> tool = find(name);
		if (tool.isEmpty()) {
			String known = String.join(", ", available().stream().map(t -> t.definition().name()).toList());
			return ToolResult.error("Unknown tool '" + name + "'. Available tools: " + known);
		}
		Args args;
		try {
			args = validate(tool.get().definition(), argumentsJson);
		}
		catch (ToolException e) {
			return ToolResult.error("Invalid arguments for " + name + ": " + e.getMessage() +
				". Expected: " + tool.get().definition().signature());
		}
		try {
			return tool.get().execute(ctx, args);
		}
		catch (ToolException e) {
			return ToolResult.error(e.getMessage());
		}
		catch (ghidrallm.util.CancellationToken.CancelledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			return ToolResult.error("Tool '" + name + "' failed unexpectedly: " + e);
		}
	}

	/** Validation step, public for testing. */
	public static Args validate(ToolDefinition def, String json) throws ToolException {
		JsonObject obj;
		try {
			JsonElement el = json == null || json.isBlank() ? new JsonObject() : JsonParser.parseString(json);
			if (!el.isJsonObject()) {
				throw new ToolException("arguments must be a JSON object");
			}
			obj = el.getAsJsonObject();
		}
		catch (JsonParseException e) {
			throw new ToolException("arguments are not valid JSON");
		}
		Map<String, ToolParam> byName = new LinkedHashMap<>();
		def.params().forEach(p -> byName.put(p.name(), p));
		for (String k : obj.keySet()) {
			if (!byName.containsKey(k)) {
				throw new ToolException("unknown argument '" + k + "' (valid: " + byName.keySet() + ")");
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (ToolParam p : def.params()) {
			JsonElement v = obj.get(p.name());
			if (v == null || v.isJsonNull() ||
				(v.isJsonPrimitive() && v.getAsString().isEmpty() && p.type() != ToolParam.Type.STRING)) {
				if (p.required()) {
					throw new ToolException("missing required argument '" + p.name() + "'");
				}
				if (p.defaultValue() != null) {
					out.put(p.name(), p.defaultValue());
				}
				continue;
			}
			out.put(p.name(), coerce(p, v));
		}
		return new Args(out);
	}

	private static Object coerce(ToolParam p, JsonElement v) throws ToolException {
		switch (p.type()) {
			case STRING: {
				if (!v.isJsonPrimitive()) {
					throw new ToolException("'" + p.name() + "' must be a string");
				}
				String s = v.getAsString();
				if (p.required() && s.isBlank()) {
					throw new ToolException("missing required argument '" + p.name() + "'");
				}
				if (!p.allowed().isEmpty() && !p.allowed().contains(s)) {
					throw new ToolException("'" + p.name() + "' must be one of " + p.allowed());
				}
				return s;
			}
			case INTEGER: {
				long n;
				try {
					String s = v.getAsString().trim();
					n = s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16)
							: Long.parseLong(s);
				}
				catch (RuntimeException e) {
					throw new ToolException("'" + p.name() + "' must be an integer");
				}
				if (p.min() != null && n < p.min() || p.max() != null && n > p.max()) {
					throw new ToolException(
						"'" + p.name() + "' must be between " + p.min() + " and " + p.max());
				}
				return n;
			}
			case BOOLEAN: {
				if (v.isJsonPrimitive()) {
					String s = v.getAsString().toLowerCase();
					if (s.equals("true") || s.equals("false")) {
						return Boolean.parseBoolean(s);
					}
				}
				throw new ToolException("'" + p.name() + "' must be true or false");
			}
			case ARRAY: {
				if (v.isJsonArray()) {
					return v.getAsJsonArray();
				}
				throw new ToolException("'" + p.name() + "' must be an array");
			}
			case OBJECT: {
				if (v.isJsonObject()) {
					return v.getAsJsonObject();
				}
				throw new ToolException("'" + p.name() + "' must be an object");
			}
		}
		throw new ToolException("unsupported type");
	}
}

package ghidrallm.tools;

import java.util.List;

import com.google.gson.*;

import ghidrallm.llm.ToolSpec;

public record ToolDefinition(String name, String description, ToolPermission permission,
		List<ToolParam> params) {

	public ToolDefinition {
		params = List.copyOf(params);
	}

	public ToolSpec toSpec() {
		return toSpec(false);
	}

	/** Compact form: first sentence of the description and no per-parameter prose (for small contexts). */
	public ToolSpec toSpec(boolean compact) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonArray required = new JsonArray();
		for (ToolParam p : params) {
			JsonObject po = new JsonObject();
			po.addProperty("type", p.type().name().toLowerCase());
			if (!compact) {
				po.addProperty("description", p.description());
			}
			if (!p.allowed().isEmpty()) {
				JsonArray en = new JsonArray();
				p.allowed().forEach(en::add);
				po.add("enum", en);
			}
			if (p.type() == ToolParam.Type.ARRAY) {
				JsonObject items = new JsonObject();
				items.addProperty("type", "object");
				po.add("items", items);
			}
			props.add(p.name(), po);
			if (p.required()) {
				required.add(p.name());
			}
		}
		schema.add("properties", props);
		schema.add("required", required);
		String d = description;
		if (compact) {
			int dot = d.indexOf(". ");
			d = dot > 0 ? d.substring(0, dot + 1) : d;
		}
		return new ToolSpec(name, d, schema);
	}

	/** Compact one-line signature for prompted (non-native) tool mode. */
	public String signature() {
		StringBuilder sb = new StringBuilder(name).append('(');
		boolean first = true;
		for (ToolParam p : params) {
			if (!first) {
				sb.append(", ");
			}
			first = false;
			sb.append(p.name()).append(p.required() ? "" : "?").append(": ")
					.append(p.type().name().toLowerCase());
		}
		return sb.append(')').toString();
	}
}

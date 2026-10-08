package ghidrallm.tools;

import java.util.List;

/** Declarative parameter description used both for the JSON schema and for argument validation. */
public record ToolParam(String name, Type type, String description, boolean required,
		List<String> allowed, Long min, Long max, Object defaultValue) {

	public enum Type { STRING, INTEGER, BOOLEAN, ARRAY, OBJECT }

	public ToolParam {
		allowed = allowed == null ? List.of() : List.copyOf(allowed);
	}

	public static ToolParam string(String name, String desc, boolean required) {
		return new ToolParam(name, Type.STRING, desc, required, null, null, null, null);
	}

	public static ToolParam enumeration(String name, String desc, boolean required,
			String... values) {
		return new ToolParam(name, Type.STRING, desc, required, List.of(values), null, null, null);
	}

	public static ToolParam integer(String name, String desc, boolean required, long min, long max,
			Long def) {
		return new ToolParam(name, Type.INTEGER, desc, required, null, min, max, def);
	}

	public static ToolParam bool(String name, String desc, boolean required, Boolean def) {
		return new ToolParam(name, Type.BOOLEAN, desc, required, null, null, null, def);
	}

	public static ToolParam array(String name, String desc, boolean required) {
		return new ToolParam(name, Type.ARRAY, desc, required, null, null, null, null);
	}
}

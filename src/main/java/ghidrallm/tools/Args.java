package ghidrallm.tools;

import java.util.Map;

import com.google.gson.JsonArray;

/** Validated, type-coerced tool arguments. */
public class Args {
	private final Map<String, Object> values;

	Args(Map<String, Object> values) {
		this.values = values;
	}

	public static Args of(Map<String, Object> values) {
		return new Args(values);
	}

	public boolean has(String k) {
		return values.containsKey(k);
	}

	public String str(String k) {
		Object v = values.get(k);
		return v == null ? null : v.toString();
	}

	public String str(String k, String def) {
		String s = str(k);
		return s == null || s.isBlank() ? def : s;
	}

	public int integer(String k, int def) {
		Object v = values.get(k);
		return v instanceof Number n ? n.intValue() : def;
	}

	public boolean bool(String k, boolean def) {
		Object v = values.get(k);
		return v instanceof Boolean b ? b : def;
	}

	public JsonArray array(String k) {
		Object v = values.get(k);
		return v instanceof JsonArray a ? a : new JsonArray();
	}
}

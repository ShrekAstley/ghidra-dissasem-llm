package ghidrallm.agent;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;

/** Parsed result of "Analyze Program": a tree of inferred subsystems plus the model's prose. */
public class ArchitectureReport {

	public static class Node {
		public String name = "";
		public String description = "";
		public String confidence = "POSSIBLE";
		public List<String> functions = new ArrayList<>();
		public List<Node> children = new ArrayList<>();

		@Override
		public String toString() {
			return name.isBlank() ? "(unnamed)" : name + (confidence.isBlank() ? "" : "  [" + confidence + "]");
		}
	}

	public final Node root = new Node();
	public String overview = "";
	public String rawText = "";

	/** Extracts the JSON block from model output; tolerant of fences and surrounding prose. */
	public static ArchitectureReport parse(String text) {
		ArchitectureReport r = new ArchitectureReport();
		r.rawText = text == null ? "" : text;
		r.root.name = "Program";
		if (text == null) {
			return r;
		}
		String json = text;
		int fence = text.indexOf("```");
		if (fence >= 0) {
			int start = text.indexOf('\n', fence);
			int end = text.indexOf("```", start + 1);
			if (start > 0 && end > start) {
				json = text.substring(start, end);
			}
		}
		int a = json.indexOf('{'), b = json.lastIndexOf('}');
		if (a < 0 || b <= a) {
			r.overview = text.trim();
			return r;
		}
		try {
			JsonObject o = JsonParser.parseString(json.substring(a, b + 1)).getAsJsonObject();
			r.overview = str(o, "overview");
			if (r.overview.isEmpty()) {
				r.overview = str(o, "flow");
			}
			JsonArray subs = o.has("subsystems") && o.get("subsystems").isJsonArray() ? o.getAsJsonArray("subsystems") : new JsonArray();
			for (JsonElement e : subs) {
				if (e.isJsonObject()) {
					r.root.children.add(node(e.getAsJsonObject(), 0));
				}
			}
			if (!(a == 0 && b == json.length() - 1) && fence < 0) {
				String prose = (text.substring(0, a) + text.substring(b + 1)).trim();
				if (r.overview.isEmpty()) {
					r.overview = prose;
				}
			}
		}
		catch (RuntimeException e) {
			r.overview = text.trim();
		}
		return r;
	}

	private static Node node(JsonObject o, int depth) {
		Node n = new Node();
		n.name = str(o, "name");
		n.description = str(o, "description");
		n.confidence = o.has("confidence") ? str(o, "confidence") : "POSSIBLE";
		if (o.has("functions") && o.get("functions").isJsonArray()) {
			for (JsonElement f : o.getAsJsonArray("functions")) {
				if (f.isJsonPrimitive()) {
					n.functions.add(f.getAsString());
				}
			}
		}
		if (depth < 4 && o.has("children") && o.get("children").isJsonArray()) {
			for (JsonElement c : o.getAsJsonArray("children")) {
				if (c.isJsonObject()) {
					n.children.add(node(c.getAsJsonObject(), depth + 1));
				}
			}
		}
		return n;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	/** ASCII tree like the one in the spec. */
	public String toTreeText() {
		StringBuilder sb = new StringBuilder("Program Architecture\n");
		render(root, "", sb);
		return sb.toString();
	}

	private static void render(Node n, String prefix, StringBuilder sb) {
		for (int i = 0; i < n.children.size(); i++) {
			Node c = n.children.get(i);
			boolean last = i == n.children.size() - 1;
			sb.append(prefix).append(last ? "└── " : "├── ").append(c.name);
			if (!c.confidence.isEmpty()) {
				sb.append("  [").append(c.confidence).append(']');
			}
			sb.append('\n');
			render(c, prefix + (last ? "    " : "│   "), sb);
		}
	}
}

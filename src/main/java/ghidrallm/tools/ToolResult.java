package ghidrallm.tools;

public record ToolResult(String text, boolean error) {
	public static ToolResult ok(String t) {
		return new ToolResult(t, false);
	}

	public static ToolResult error(String t) {
		return new ToolResult("ERROR: " + t, true);
	}
}

package ghidrallm.tools;

public interface Tool {
	ToolDefinition definition();

	ToolResult execute(ToolContext ctx, Args args) throws ToolException;
}

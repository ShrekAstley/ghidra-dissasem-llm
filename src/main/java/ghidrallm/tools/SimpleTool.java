package ghidrallm.tools;

import java.util.List;

/** Lambda-friendly {@link Tool}. */
public final class SimpleTool implements Tool {
	@FunctionalInterface
	public interface Body {
		ToolResult run(ToolContext ctx, Args args) throws ToolException;
	}

	private final ToolDefinition def;
	private final Body body;

	public SimpleTool(String name, ToolPermission perm, String description, List<ToolParam> params,
			Body body) {
		this.def = new ToolDefinition(name, description, perm, params);
		this.body = body;
	}

	public static SimpleTool read(String name, String description, List<ToolParam> params, Body body) {
		return new SimpleTool(name, ToolPermission.READ_PROGRAM, description, params, body);
	}

	@Override
	public ToolDefinition definition() {
		return def;
	}

	@Override
	public ToolResult execute(ToolContext ctx, Args args) throws ToolException {
		return body.run(ctx, args);
	}
}

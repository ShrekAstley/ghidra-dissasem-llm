package ghidrallm.llm;

import java.util.List;

public record ChatResponse(String content, List<ToolCall> toolCalls, String finishReason, Usage usage,
		long elapsedMillis, String rawBody, String providerBlocks) {

	public ChatResponse(String content, List<ToolCall> toolCalls, String finishReason, Usage usage,
			long elapsedMillis, String rawBody) {
		this(content, toolCalls, finishReason, usage, elapsedMillis, rawBody, null);
	}

	public ChatResponse {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		content = content == null ? "" : content;
	}
}

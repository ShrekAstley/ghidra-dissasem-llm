package ghidrallm.llm;

import java.util.List;

/** One message in an OpenAI-style chat. */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
		String name) {

	public static final String SYSTEM = "system";
	public static final String USER = "user";
	public static final String ASSISTANT = "assistant";
	public static final String TOOL = "tool";

	public ChatMessage {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public static ChatMessage system(String c) {
		return new ChatMessage(SYSTEM, c, null, null, null);
	}

	public static ChatMessage user(String c) {
		return new ChatMessage(USER, c, null, null, null);
	}

	public static ChatMessage assistant(String c) {
		return new ChatMessage(ASSISTANT, c, null, null, null);
	}

	public static ChatMessage assistantWithCalls(String c, List<ToolCall> calls) {
		return new ChatMessage(ASSISTANT, c, calls, null, null);
	}

	public static ChatMessage tool(String callId, String name, String c) {
		return new ChatMessage(TOOL, c, null, callId, name);
	}

	public ChatMessage withContent(String newContent) {
		return new ChatMessage(role, newContent, toolCalls, toolCallId, name);
	}
}

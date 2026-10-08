package ghidrallm.llm;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
		double temperature, int maxTokens) {
	public ChatRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
	}
}

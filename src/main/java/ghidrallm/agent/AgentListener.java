package ghidrallm.agent;

import ghidrallm.llm.Usage;

/** Progress events from the agent loop; all callbacks occur on the agent thread. */
public interface AgentListener {
	default void onStatus(String status) {}

	default void onAssistantText(String text) {}

	default void onToolCall(String name, String argsJson) {}

	default void onToolResult(String name, String result, boolean error, long millis) {}

	default void onUsage(Usage turn, Usage total) {}

	AgentListener NONE = new AgentListener() {};
}

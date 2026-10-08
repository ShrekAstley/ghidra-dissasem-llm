package ghidrallm.llm;

/** A model-requested tool invocation. Arguments stay as raw JSON until validated. */
public record ToolCall(String id, String name, String argumentsJson) {}

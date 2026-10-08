package ghidrallm.llm;

public record Usage(int promptTokens, int completionTokens, int totalTokens) {
	public static final Usage NONE = new Usage(0, 0, 0);

	public Usage plus(Usage o) {
		return new Usage(promptTokens + o.promptTokens, completionTokens + o.completionTokens,
			totalTokens + o.totalTokens);
	}
}

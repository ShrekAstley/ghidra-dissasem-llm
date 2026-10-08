package ghidrallm.agent;

import ghidrallm.llm.Usage;

public record AgentResult(Status status, String answer, String hiddenReasoning, int toolCalls, int steps,
		Usage usage, long elapsedMillis, String error) {

	public enum Status { COMPLETED, CANCELLED, ERROR, LIMIT_REACHED }

	public boolean ok() {
		return status == Status.COMPLETED || status == Status.LIMIT_REACHED;
	}
}

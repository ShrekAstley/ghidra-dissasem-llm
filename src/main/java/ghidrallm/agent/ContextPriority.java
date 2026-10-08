package ghidrallm.agent;

/** Retrieval priority; lower value = kept first when the context budget is tight. */
public enum ContextPriority {
	CURRENT_FUNCTION(1), USER_QUESTION(2), ASSEMBLY(3), DECOMPILATION(4), CALLERS_CALLEES(5), STRINGS(6),
	GLOBALS(7), XREFS(8), RELATED_FUNCTIONS(9), BROADER_PROGRAM(10);

	public final int rank;

	ContextPriority(int rank) {
		this.rank = rank;
	}
}

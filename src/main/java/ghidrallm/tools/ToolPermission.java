package ghidrallm.tools;

/** Explicit capability classes. The model never gets anything outside this list. */
public enum ToolPermission {
	/** Read-only inspection of the open Ghidra program. */
	READ_PROGRAM,
	/** Queue a change proposal (does NOT modify the program; user approval is required). */
	PROPOSE_CHANGE,
	/** Read/write the local analysis-knowledge database (not the Ghidra program). */
	LOCAL_KNOWLEDGE
}

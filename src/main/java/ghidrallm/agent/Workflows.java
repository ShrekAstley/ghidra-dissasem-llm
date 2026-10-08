package ghidrallm.agent;

/** Prompt templates for the one-click workflows. */
public final class Workflows {
	private Workflows() {}

	public static final String EXPLAIN = """
			Explain what the selected function does in plain language. Use the supplied context first; call tools (callers, strings, xrefs, callees) only if they would change your conclusion. \
			Give: a 2-3 sentence summary, then key evidence as bullets, each claim labelled CONFIRMED/LIKELY/POSSIBLE/UNKNOWN.""";

	public static final String ANALYZE = """
			Perform a full analysis of the selected function. Compare the decompiler output with the assembly, consider the calling convention, callers, callees, constants and strings, and inspect additional functions with tools when needed. \
			Respond with exactly these sections (use UNKNOWN where you cannot tell):
			Function Purpose
			Inputs
			Outputs
			Side Effects
			Important Variables
			Important Constants
			Called Functions
			Calling Functions
			Referenced Strings
			Likely Data Structures
			Control Flow Summary
			Confidence (percentage plus one sentence of justification)
			Unknowns
			Support every non-trivial statement with an 'Evidence:' bullet and a CONFIRMED/LIKELY/POSSIBLE/UNKNOWN label.""";

	public static final String SECURITY = """
			Perform a security review of the selected function. Look for: unsafe memory operations, unchecked sizes, format-string use, dangerous APIs, weak validation, integer overflow, use-after-free / double-free patterns, command execution paths, suspicious deserialization, hardcoded secrets and cryptographic misuse. \
			Inspect callers/callees and data flow with tools to check whether inputs are attacker-controlled. Do NOT present guesses as confirmed vulnerabilities. \
			For each finding use exactly: Potential Issue / Evidence / Relevant Function / Confidence (CONFIRMED|LIKELY|POSSIBLE|UNKNOWN) / Why It Matters / What Would Confirm It. If you find nothing supported by evidence, say so.""";

	public static final String TRACE_TEMPLATE = """
			Trace the value of the variable '%s' in the selected function. Use the trace_value tool backward (origin) %s. \
			Report the chain: current use -> source operand -> previous assignments -> function arguments -> callers -> origin. \
			Clearly label each step as VERIFIED (from Ghidra data-flow) or INFERRED.""";

	public static final String COMMENT = """
			Generate a function comment for the selected function. Inspect it as needed, then call propose_function_comment with a concise block comment (purpose, key parameters, return value, notable side effects; hedge uncertain claims). \
			Do not claim more than the evidence supports. After proposing, briefly tell the user what you proposed.""";

	public static final String NAMES = """
			Propose better names for the selected function and for its local variables/parameters, but only where the evidence supports a specific meaning. Inspect the function (get_function_variables gives the exact variable names) and then call propose_rename_function / propose_rename_variable for each rename. \
			Skip names that are already meaningful analyst-chosen names. Explain each proposal in one line with its evidence.""";

	public static final String TYPES = """
			Examine how the selected function uses its pointer arguments and globals (offsets, field sizes, access patterns, constants). Infer data structures only where accesses support them. \
			For each well-supported structure call propose_structure (and propose_function_signature where parameter types are clear). Mark guessed fields as such in comments and tell the user what is uncertain.""";

	public static final String RELATIONSHIPS = """
			Explain how the selected function relates to its callers, callees and the globals it touches. Follow chains such as A calls B, B writes global X, C reads X (use get_global, get_xrefs_to, get_callers). \
			State which links are VERIFIED from Ghidra references and which are INFERRED.""";

	public static String trace(String variable, boolean forwardToo) {
		return TRACE_TEMPLATE.formatted(variable, forwardToo ? "and forward (uses)" : "");
	}
}

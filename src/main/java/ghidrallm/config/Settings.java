package ghidrallm.config;

/** All user-configurable values. Plain data (Gson-serializable); defaults are privacy-preserving. */
public class Settings {
	// --- LLM endpoint / generation ---
	public String endpoint = "http://localhost:1234/v1";
	/** Empty = use whichever model LM Studio reports first / has loaded. */
	public String model = "";
	public double temperature = 0.2;
	public int maxOutputTokens = 2048;
	/** Total tokens the prompt may occupy. Should be &lt;= the context length loaded in LM Studio. */
	public int contextBudgetTokens = 16384;
	public int requestTimeoutSeconds = 180;
	/** Allow a non-loopback endpoint (e.g. LM Studio on another LAN machine). Off by default. */
	public boolean allowNonLoopbackEndpoint = false;
	/** AUTO, NATIVE or PROMPTED. */
	public String toolMode = "AUTO";

	// --- Agent limits ---
	public int maxToolCalls = 16;
	public int maxAgentSteps = 12;
	public int maxRepeatedCalls = 2;
	public int maxToolResultChars = 6000;
	public int agentTimeoutSeconds = 600;

	// --- Tool permissions ---
	public boolean allowProposalTools = true;
	public boolean allowKnowledgeTools = true;

	// --- UI ---
	public boolean expertMode = false;
	public boolean includeContextOnSelection = true;
	public boolean maskSensitiveInLogs = false;

	// --- Analysis ---
	public int programAnalysisMaxFunctions = 25;
	public boolean persistKnowledge = true;

	public Settings copy() {
		return new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(this),
			Settings.class);
	}

	/** Clamps values into safe ranges. */
	public void normalize() {
		if (endpoint == null || endpoint.isBlank()) {
			endpoint = "http://localhost:1234/v1";
		}
		endpoint = endpoint.trim().replaceAll("/+$", "");
		if (model == null) {
			model = "";
		}
		temperature = Math.max(0.0, Math.min(2.0, temperature));
		maxOutputTokens = clamp(maxOutputTokens, 64, 65536);
		contextBudgetTokens = clamp(contextBudgetTokens, 1024, 1_000_000);
		requestTimeoutSeconds = clamp(requestTimeoutSeconds, 5, 3600);
		maxToolCalls = clamp(maxToolCalls, 0, 100);
		maxAgentSteps = clamp(maxAgentSteps, 1, 50);
		maxRepeatedCalls = clamp(maxRepeatedCalls, 1, 10);
		maxToolResultChars = clamp(maxToolResultChars, 500, 100_000);
		agentTimeoutSeconds = clamp(agentTimeoutSeconds, 10, 7200);
		programAnalysisMaxFunctions = clamp(programAnalysisMaxFunctions, 1, 200);
		if (toolMode == null || !(toolMode.equals("AUTO") || toolMode.equals("NATIVE") ||
			toolMode.equals("PROMPTED"))) {
			toolMode = "AUTO";
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}

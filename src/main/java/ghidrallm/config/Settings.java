package ghidrallm.config;

/** All user-configurable values. Plain data (Gson-serializable); defaults are privacy-preserving. */
public class Settings {
	// --- LLM provider ---
	/** LMSTUDIO (local, default), OPENAI_COMPATIBLE (OpenAI, OpenRouter, Ollama, vLLM, ...), or ANTHROPIC. */
	public String providerType = "LMSTUDIO";
	/** Name of an environment variable holding the API key (preferred over a stored key). Remote providers only. */
	public String apiKeyEnv = "";
	/**
	 * Remote hosts the user has explicitly agreed to send program data to. Loopback needs no consent.
	 * Only the settings UI adds entries (after showing a warning); the model can never change this.
	 */
	public java.util.List<String> remoteConsentHosts = new java.util.ArrayList<>();
	/** Optional reasoning effort for providers that support it (blank = provider default). */
	public String effort = "";
	/** Remembered endpoint/model/key-env per provider so switching providers does not lose values. */
	public java.util.Map<String, Profile> profiles = new java.util.LinkedHashMap<>();

	public static class Profile {
		public String endpoint = "";
		public String model = "";
		public String apiKeyEnv = "";
	}

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

	// --- MCP server (exposes the Ghidra tools to external MCP clients, loopback only) ---
	public boolean mcpEnabled = false;
	public int mcpPort = 8765;
	/** Let MCP clients queue change proposals (still need approval in Ghidra). */
	public boolean mcpAllowProposals = false;
	/** Let MCP clients read/write the local knowledge notes. */
	public boolean mcpAllowKnowledge = false;

	// --- Analysis ---
	public int programAnalysisMaxFunctions = 25;
	public boolean persistKnowledge = true;

	public boolean isRemote() {
		return !ghidrallm.llm.EndpointPolicy.isLoopbackUrl(endpoint);
	}

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
		if (providerType == null || !(providerType.equals("LMSTUDIO") || providerType.equals("OPENAI_COMPATIBLE") ||
			providerType.equals("ANTHROPIC"))) {
			providerType = "LMSTUDIO";
		}
		if (apiKeyEnv == null) {
			apiKeyEnv = "";
		}
		apiKeyEnv = apiKeyEnv.trim();
		if (effort == null || !java.util.List.of("", "low", "medium", "high", "xhigh", "max").contains(effort)) {
			effort = "";
		}
		if (remoteConsentHosts == null) {
			remoteConsentHosts = new java.util.ArrayList<>();
		}
		if (profiles == null) {
			profiles = new java.util.LinkedHashMap<>();
		}
		mcpPort = clamp(mcpPort, 1024, 65535);
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

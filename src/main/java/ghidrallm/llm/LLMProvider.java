package ghidrallm.llm;

import java.util.List;

import ghidrallm.util.CancellationToken;

/**
 * Provider abstraction. Only local providers belong here; the first implementation is
 * {@link LMStudioProvider}. Implementations must be thread-safe and must never contact a remote host.
 */
public interface LLMProvider {

	/** Short identifier, e.g. "lmstudio". */
	String id();

	/** Models currently available on the server. */
	List<String> listModels() throws LLMException;

	/**
	 * Sends a non-streaming chat completion. Implementations must honor cancellation promptly and
	 * map transport failures to {@link LLMException}.
	 */
	ChatResponse chat(ChatRequest request, CancellationToken cancel) throws LLMException;

	/** Runs the three-step connection test used by the settings dialog. */
	ConnectionReport testConnection(String model);
}

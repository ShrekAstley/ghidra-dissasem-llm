package ghidrallm.llm;

/** Provider failure with a category the UI can turn into an actionable message. */
public class LLMException extends Exception {
	public enum Kind {
		UNREACHABLE("LM Studio is not reachable. Start LM Studio and enable the local server."),
		NO_MODEL("No model is loaded. Load a model in LM Studio."),
		TIMEOUT("The model took too long to respond. Increase the request timeout or use a smaller model."),
		BAD_REQUEST("LM Studio rejected the request."),
		CONTEXT_TOO_LARGE("The prompt exceeds the model's context window. Lower the context budget or reload the model with a larger context."),
		INVALID_RESPONSE("LM Studio returned a response that could not be understood."),
		ENDPOINT_NOT_LOCAL("The configured endpoint is a remote host you have not approved. Open Settings and confirm that program data may be sent to it, or switch back to a local provider."),
		AUTH_FAILED("The provider rejected the API key. Check the key (or the environment variable that holds it) in Settings."),
		NO_API_KEY("No API key is configured for this provider. Set an API key (or the name of an environment variable that holds it) in Settings."),
		RATE_LIMITED("The provider is rate limiting or overloaded. Wait a moment and retry, or lower the tool-call limit."),
		CANCELLED("Cancelled."),
		SERVER_ERROR("LM Studio reported an internal error.");

		public final String help;

		Kind(String help) {
			this.help = help;
		}
	}

	private final Kind kind;
	private final int httpStatus;

	public LLMException(Kind kind, String detail) {
		this(kind, detail, 0, null);
	}

	public LLMException(Kind kind, String detail, int httpStatus, Throwable cause) {
		super(detail == null || detail.isBlank() ? kind.help : kind.help + " (" + detail + ")", cause);
		this.kind = kind;
		this.httpStatus = httpStatus;
	}

	public Kind getKind() {
		return kind;
	}

	public int getHttpStatus() {
		return httpStatus;
	}
}

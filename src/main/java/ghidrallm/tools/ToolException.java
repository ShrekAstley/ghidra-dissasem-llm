package ghidrallm.tools;

/** Expected, user-explainable tool failure (bad arg, function not found, ...). */
public class ToolException extends Exception {
	public ToolException(String msg) {
		super(msg);
	}

	public ToolException(String msg, Throwable cause) {
		super(msg, cause);
	}
}

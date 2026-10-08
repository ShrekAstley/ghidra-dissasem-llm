package ghidrallm.changes;

public class ChangeException extends Exception {
	public ChangeException(String msg) {
		super(msg);
	}

	public ChangeException(String msg, Throwable cause) {
		super(msg, cause);
	}
}

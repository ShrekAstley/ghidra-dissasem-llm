package ghidrallm.changes;

/**
 * Validation finding. ERROR blocks applying; OVERWRITE replaces analyst-authored data and needs
 * explicit confirmation; WARNING is informational.
 */
public record Problem(Severity severity, String message) {
	public enum Severity { ERROR, OVERWRITE, WARNING }

	public static Problem error(String m) {
		return new Problem(Severity.ERROR, m);
	}

	public static Problem overwrite(String m) {
		return new Problem(Severity.OVERWRITE, m);
	}

	public static Problem warning(String m) {
		return new Problem(Severity.WARNING, m);
	}
}

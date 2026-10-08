package ghidrallm.util;

/** Small string helpers shared across modules. */
public final class Text {
	private Text() {}

	/** Rough token estimate; local tokenizers differ, so this is deliberately conservative. */
	public static int estimateTokens(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		return (int) Math.ceil(s.length() / 3.2);
	}

	public static String truncate(String s, int maxChars) {
		if (s == null || s.length() <= maxChars) {
			return s;
		}
		if (maxChars <= 0) {
			return "";
		}
		return s.substring(0, maxChars) + "\n...[truncated " + (s.length() - maxChars) + " chars]";
	}

	/** Keeps the head and tail of a long text, eliding the middle. */
	public static String truncateMiddle(String s, int maxChars) {
		if (s == null || s.length() <= maxChars) {
			return s;
		}
		int half = Math.max(1, maxChars / 2);
		return s.substring(0, half) + "\n...[" + (s.length() - 2 * half) + " chars elided]...\n" +
			s.substring(s.length() - half);
	}

	public static String oneLine(String s, int max) {
		if (s == null) {
			return "";
		}
		String t = s.replaceAll("\\s+", " ").trim();
		return t.length() <= max ? t : t.substring(0, max) + "…";
	}

	public static String escapeHtml(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> sb.append("&amp;");
				case '<' -> sb.append("&lt;");
				case '>' -> sb.append("&gt;");
				case '"' -> sb.append("&quot;");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}
}

package ghidrallm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans model output for display and extracts the evidence labels the prompts require. */
public final class ResponseParser {
	private ResponseParser() {}

	public record Parsed(String answer, String hiddenReasoning, List<String> labels) {}

	private static final Pattern THINK = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);
	private static final Pattern LABEL = Pattern.compile("\\b(CONFIRMED|LIKELY|POSSIBLE|UNKNOWN)\\b");

	/**
	 * Removes reasoning-model {@code <think>} blocks from the visible answer. They are retained only
	 * so expert mode can show that they exist; the plugin never relies on them.
	 */
	public static Parsed parse(String content) {
		if (content == null) {
			return new Parsed("", "", List.of());
		}
		StringBuilder hidden = new StringBuilder();
		Matcher m = THINK.matcher(content);
		while (m.find()) {
			hidden.append(m.group(1).trim()).append('\n');
		}
		String answer = m.replaceAll("");
		int close = answer.indexOf("</think>");
		if (close >= 0) {
			hidden.append(answer, 0, close);
			answer = answer.substring(close + 8);
		}
		int open = answer.indexOf("<think>");
		if (open >= 0) {
			answer = answer.substring(0, open);
		}
		List<String> labels = new ArrayList<>();
		Matcher lm = LABEL.matcher(answer);
		while (lm.find()) {
			labels.add(lm.group(1));
		}
		return new Parsed(answer.trim(), hidden.toString().trim(), labels);
	}
}

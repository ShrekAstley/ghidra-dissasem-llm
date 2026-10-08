package ghidrallm.agent;

import java.util.*;

import ghidrallm.llm.ChatMessage;
import ghidrallm.util.Text;

/**
 * Budgeting for local models with small context windows. Two jobs:
 * <ol>
 * <li>{@link #assemble}: choose which context items to include (priority order, dedupe, truncation).</li>
 * <li>{@link #fit}: shrink the running conversation to fit the prompt budget.</li>
 * </ol>
 */
public class ContextManager {

	/** Result of assembling a context block. */
	public record Block(String text, List<String> includedKeys, List<String> omittedTitles,
			List<String> truncatedTitles, int estimatedTokens) {}

	private static final int MIN_USEFUL_TOKENS = 120;

	/**
	 * @param alreadySent keys of items delivered earlier in this conversation (not resent)
	 * @param maxTokens   budget for the whole block
	 */
	public Block assemble(List<ContextItem> items, Set<String> alreadySent, int maxTokens) {
		List<ContextItem> sorted = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		Set<Integer> hashes = new HashSet<>();
		List<String> unchanged = new ArrayList<>();
		for (ContextItem it : items) {
			if (it.text() == null || it.text().isBlank()) {
				continue;
			}
			if (alreadySent.contains(it.key())) {
				unchanged.add(it.title());
				continue;
			}
			if (!keys.add(it.key()) || !hashes.add(it.text().hashCode())) {
				continue;
			}
			sorted.add(it);
		}
		sorted.sort(Comparator.comparingInt(i -> i.priority().rank));

		int perItemCap = Math.max(MIN_USEFUL_TOKENS, (int) (maxTokens * 0.45));
		int remaining = maxTokens;
		StringBuilder sb = new StringBuilder();
		List<String> included = new ArrayList<>();
		List<String> omitted = new ArrayList<>();
		List<String> truncated = new ArrayList<>();
		for (ContextItem it : sorted) {
			String header = "### " + it.title() + "\n";
			int headerTokens = Text.estimateTokens(header);
			int need = Text.estimateTokens(it.text()) + headerTokens;
			int allowed = Math.min(remaining, perItemCap + headerTokens);
			if (need <= allowed) {
				sb.append(header).append(it.text().stripTrailing()).append("\n\n");
				remaining -= need;
				included.add(it.key());
			}
			else if (allowed >= MIN_USEFUL_TOKENS) {
				int chars = (int) ((allowed - headerTokens) * 3.2);
				sb.append(header).append(Text.truncateMiddle(it.text().stripTrailing(), chars)).append("\n\n");
				remaining -= allowed;
				included.add(it.key());
				truncated.add(it.title() + (it.fetchHint() != null ? " (fetch more: " + it.fetchHint() + ")" : ""));
			}
			else {
				omitted.add(it.title() + (it.fetchHint() != null ? " (fetch: " + it.fetchHint() + ")" : ""));
			}
		}
		StringBuilder notes = new StringBuilder();
		if (!unchanged.isEmpty()) {
			notes.append("[Already provided earlier in this conversation and unchanged: ")
					.append(String.join(", ", unchanged)).append("]\n");
		}
		if (!truncated.isEmpty()) {
			notes.append("[Truncated to fit the context budget: ").append(String.join("; ", truncated)).append("]\n");
		}
		if (!omitted.isEmpty()) {
			notes.append("[Omitted for budget; use tools if needed: ").append(String.join("; ", omitted)).append("]\n");
		}
		String text = (sb.toString() + notes).stripTrailing();
		return new Block(text, included, omitted, truncated, Text.estimateTokens(text));
	}

	/**
	 * Returns a message list (system first) that fits {@code budgetTokens}. Strategy: (1) shrink old
	 * tool results, (2) drop whole oldest turns, (3) shrink tool results in the current turn.
	 * The latest user message is never dropped.
	 */
	public List<ChatMessage> fit(ChatMessage system, List<List<ChatMessage>> turns, int budgetTokens,
			int fixedOverheadTokens) {
		List<List<ChatMessage>> work = new ArrayList<>();
		for (List<ChatMessage> t : turns) {
			work.add(new ArrayList<>(t));
		}
		int budget = budgetTokens - fixedOverheadTokens - Text.estimateTokens(system.content());
		// 1. elide old tool results (all turns but the last)
		for (int i = 0; i < work.size() - 1 && total(work) > budget; i++) {
			List<ChatMessage> t = work.get(i);
			for (int j = 0; j < t.size(); j++) {
				ChatMessage m = t.get(j);
				if (isToolResult(m) && m.content().length() > 200) {
					t.set(j, m.withContent("[earlier tool result elided: " + m.content().length() +
						" chars; call the tool again if needed]"));
				}
			}
		}
		// 2. drop oldest turns
		boolean dropped = false;
		while (work.size() > 1 && total(work) > budget) {
			work.remove(0);
			dropped = true;
		}
		// 3. shrink current-turn tool results, largest first
		if (total(work) > budget && !work.isEmpty()) {
			List<ChatMessage> cur = work.get(work.size() - 1);
			for (int pass = 0; pass < 8 && total(work) > budget; pass++) {
				int big = -1;
				for (int j = 0; j < cur.size(); j++) {
					if (cur.get(j).content() != null && (big < 0 || cur.get(j).content().length() > cur.get(big).content().length())
						&& (isToolResult(cur.get(j)) || j == 0)) {
						big = j;
					}
				}
				if (big < 0 || cur.get(big).content().length() < 400) {
					break;
				}
				ChatMessage m = cur.get(big);
				cur.set(big, m.withContent(Text.truncateMiddle(m.content(), m.content().length() / 2)));
			}
		}
		List<ChatMessage> out = new ArrayList<>();
		out.add(system);
		if (dropped) {
			out.add(ChatMessage.system("[Earlier conversation turns were dropped to fit the context window. " +
				"Session memory above lists what was already analyzed.]"));
		}
		for (List<ChatMessage> t : work) {
			for (ChatMessage m : t) {
				// Many chat templates reject consecutive same-role user messages; merge them.
				ChatMessage prev = out.isEmpty() ? null : out.get(out.size() - 1);
				if (prev != null && ChatMessage.USER.equals(m.role()) && ChatMessage.USER.equals(prev.role())) {
					out.set(out.size() - 1, prev.withContent(prev.content() + "\n\n" + m.content()));
				}
				else {
					out.add(m);
				}
			}
		}
		return out;
	}

	private static boolean isToolResult(ChatMessage m) {
		return ChatMessage.TOOL.equals(m.role()) ||
			(ChatMessage.USER.equals(m.role()) && m.content() != null && m.content().startsWith("<tool_response"));
	}

	private static int total(List<List<ChatMessage>> turns) {
		int n = 0;
		for (List<ChatMessage> t : turns) {
			for (ChatMessage m : t) {
				n += Text.estimateTokens(m.content()) + 6;
				for (var c : m.toolCalls()) {
					n += Text.estimateTokens(c.argumentsJson()) + 10;
				}
			}
		}
		return n;
	}
}

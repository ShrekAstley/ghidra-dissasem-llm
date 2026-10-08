package ghidrallm.agent;

import java.util.*;

import ghidrallm.llm.ChatMessage;
import ghidrallm.util.Text;

/**
 * Session memory: ordered turns of messages, the set of context blocks already sent (so they are
 * not resent), and a compact "what we analyzed" memory used to resolve follow-ups like "the second
 * caller".
 */
public class Conversation {

	public static final class Turn {
		final List<ChatMessage> messages = new ArrayList<>();
		final Set<String> addedContextKeys = new LinkedHashSet<>();
		String displayUser = "";
		String focus = "";
		String summary = "";

		public String displayUser() {
			return displayUser;
		}

		public List<ChatMessage> messagesView() {
			return messages;
		}
	}

	private final List<Turn> turns = new ArrayList<>();
	private final Set<String> sentContext = new HashSet<>();
	private final Map<String, String> memory = new LinkedHashMap<>();

	public synchronized Turn startTurn(String displayUser, String focus, ChatMessage userMessage,
			Collection<String> contextKeys) {
		Turn t = new Turn();
		t.displayUser = displayUser;
		t.focus = focus == null ? "" : focus;
		t.messages.add(userMessage);
		t.addedContextKeys.addAll(contextKeys);
		sentContext.addAll(contextKeys);
		turns.add(t);
		return t;
	}

	public synchronized void append(Turn t, ChatMessage m) {
		t.messages.add(m);
	}

	/** Records the final answer of a turn and updates session memory for its focus. */
	public synchronized void finishTurn(Turn t, String finalAnswer) {
		t.summary = Text.oneLine(finalAnswer, 220);
		if (!t.focus.isBlank() && !t.summary.isBlank()) {
			memory.remove(t.focus);
			memory.put(t.focus, t.summary);
			while (memory.size() > 12) {
				memory.remove(memory.keySet().iterator().next());
			}
		}
	}

	/** Removes the last turn (for regenerate); returns it or null. */
	public synchronized Turn popLastTurn() {
		if (turns.isEmpty()) {
			return null;
		}
		Turn t = turns.remove(turns.size() - 1);
		sentContext.removeAll(t.addedContextKeys);
		if (!t.focus.isBlank()) {
			memory.remove(t.focus);
		}
		return t;
	}

	public synchronized void clear() {
		turns.clear();
		sentContext.clear();
		memory.clear();
	}

	public synchronized boolean isEmpty() {
		return turns.isEmpty();
	}

	public synchronized Set<String> sentContextKeys() {
		return new HashSet<>(sentContext);
	}

	public synchronized List<List<ChatMessage>> turnMessages() {
		List<List<ChatMessage>> out = new ArrayList<>();
		for (Turn t : turns) {
			out.add(new ArrayList<>(t.messages));
		}
		return out;
	}

	public synchronized List<Turn> turns() {
		return new ArrayList<>(turns);
	}

	/** One-line-per-item memory of earlier analysis, or "" if none. */
	public synchronized String memoryBlock() {
		if (memory.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("SESSION MEMORY (earlier conclusions in this conversation; unverified model output):\n");
		for (var e : memory.entrySet()) {
			sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
		}
		return sb.toString();
	}
}

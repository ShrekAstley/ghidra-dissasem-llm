package ghidrallm.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory, local-only debug log. Entries that contain program-derived content (decompiled code,
 * memory, strings, prompts) are flagged {@code sensitive} so the UI can mask them.
 */
public class DebugLog {

	public enum Category { LLM_REQUEST, LLM_RESPONSE, TOOL_CALL, TOOL_RESULT, AGENT, TIMING, ERROR, INFO, CHANGE }

	public record Entry(long id, Instant time, Category category, String summary, String detail,
			boolean sensitive) {}

	private final List<Entry> entries = new ArrayList<>();
	private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
	private final int capacity;
	private long nextId = 1;

	public DebugLog() {
		this(2000);
	}

	public DebugLog(int capacity) {
		this.capacity = capacity;
	}

	public synchronized Entry log(Category cat, String summary, String detail, boolean sensitive) {
		Entry e = new Entry(nextId++, Instant.now(), cat, summary, detail == null ? "" : detail,
			sensitive);
		entries.add(e);
		while (entries.size() > capacity) {
			entries.remove(0);
		}
		for (Consumer<Entry> l : listeners) {
			l.accept(e);
		}
		return e;
	}

	public Entry info(String msg) {
		return log(Category.INFO, msg, "", false);
	}

	public Entry error(String msg, Throwable t) {
		return log(Category.ERROR, msg, t == null ? "" : stack(t), false);
	}

	public synchronized List<Entry> snapshot() {
		return new ArrayList<>(entries);
	}

	public synchronized void clear() {
		entries.clear();
	}

	public void addListener(Consumer<Entry> l) {
		listeners.add(l);
	}

	public void removeListener(Consumer<Entry> l) {
		listeners.remove(l);
	}

	/** Formats the log for export; sensitive details are replaced when {@code redact} is set. */
	public String export(boolean redact) {
		StringBuilder sb = new StringBuilder();
		for (Entry e : snapshot()) {
			sb.append(e.time()).append(" [").append(e.category()).append("] ").append(e.summary());
			if (e.sensitive()) {
				sb.append("  (SENSITIVE: contains program data)");
			}
			sb.append('\n');
			if (!e.detail().isEmpty()) {
				sb.append(redact && e.sensitive() ? "    <redacted " + e.detail().length() + " chars>\n"
						: indent(e.detail()));
			}
		}
		return sb.toString();
	}

	private static String indent(String s) {
		return "    " + s.replace("\n", "\n    ") + "\n";
	}

	private static String stack(Throwable t) {
		java.io.StringWriter sw = new java.io.StringWriter();
		t.printStackTrace(new java.io.PrintWriter(sw));
		return sw.toString();
	}
}

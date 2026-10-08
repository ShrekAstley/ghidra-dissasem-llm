package ghidrallm.agent;

/** One candidate piece of context. {@code key} identifies it for deduplication across turns. */
public record ContextItem(ContextPriority priority, String key, String title, String text,
		String fetchHint) {
	public ContextItem(ContextPriority priority, String key, String title, String text) {
		this(priority, key, title, text, null);
	}
}

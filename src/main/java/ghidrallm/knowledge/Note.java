package ghidrallm.knowledge;

/** One persisted piece of analysis knowledge. */
public record Note(long id, String programKey, Kind kind, String subject, String address, String text,
		Source source, String confidence, String createdAt) {

	public enum Kind { FUNCTION_SUMMARY, HYPOTHESIS, RELATIONSHIP, SUBSYSTEM, APPROVED_CHANGE, USER_NOTE, ARCHITECTURE }

	/** AI = unverified model output; APPROVED = result of a user-approved change; USER = analyst-written. */
	public enum Source { AI, APPROVED, USER }
}

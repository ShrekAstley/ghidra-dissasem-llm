package ghidrallm.changes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ghidra.program.model.listing.Program;

/**
 * A proposed modification of the Ghidra database. Creating a proposal never touches the program;
 * only {@link ProposalManager#approve} (after user approval + validation) calls {@link #apply}.
 */
public abstract class ChangeProposal {

	public enum Kind {
		RENAME_FUNCTION, RENAME_VARIABLE, FUNCTION_SIGNATURE, COMMENT, CREATE_LABEL, CREATE_STRUCTURE,
		CREATE_ENUM, APPLY_DATA_TYPE
	}

	public enum Status { PENDING, APPLIED, REJECTED, FAILED }

	private static final AtomicInteger COUNTER = new AtomicInteger();

	private final String id = "P" + COUNTER.incrementAndGet();
	private final Kind kind;
	private String reason = "";
	private String confidence = "";
	private volatile Status status = Status.PENDING;
	private volatile String resultMessage = "";

	protected ChangeProposal(Kind kind) {
		this.kind = kind;
	}

	public String id() {
		return id;
	}

	public Kind kind() {
		return kind;
	}

	public String reason() {
		return reason;
	}

	public String confidence() {
		return confidence;
	}

	public ChangeProposal withRationale(String reason, String confidence) {
		this.reason = reason == null ? "" : reason;
		this.confidence = confidence == null ? "" : confidence;
		return this;
	}

	public Status status() {
		return status;
	}

	public String resultMessage() {
		return resultMessage;
	}

	void setStatus(Status s, String msg) {
		this.status = s;
		this.resultMessage = msg == null ? "" : msg;
	}

	/** One-line title, e.g. "Rename FUN_00401000 → load_asset". */
	public abstract String title();

	/** Multi-line human preview of what will change (reads the program for the current state). */
	public abstract String preview(Program program);

	/** Checks the proposal against the current program state. Must not modify the program. */
	public abstract List<Problem> validate(ApplyContext ctx);

	/** Performs the change. Called inside a program transaction by the manager. */
	protected abstract void apply(ApplyContext ctx) throws ChangeException;

	/** Key used to suppress duplicate pending proposals. */
	public abstract String dedupeKey();

	/** Text the user may edit before approving, or null if not editable. */
	public String editableText() {
		return null;
	}

	/** Replaces the proposal content with user-edited text. */
	public void applyEdit(String text) throws ChangeException {
		throw new ChangeException("This proposal is not editable.");
	}

	/** Short statement stored as knowledge after a successful apply. */
	public String knowledgeSummary() {
		return title();
	}

	/** Address text associated with the change (for knowledge indexing); may be null. */
	public String addressText() {
		return null;
	}
}

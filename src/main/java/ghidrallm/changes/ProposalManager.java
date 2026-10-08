package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ghidra.program.model.listing.Program;
import ghidrallm.ghidra.DecompilerService;

/**
 * Central gate for every database modification: Proposal → Preview → User approval → Validation →
 * Apply (inside one undoable Ghidra transaction).
 */
public class ProposalManager {

	/** Outcome of an approve call. {@code needsConfirmation} means an OVERWRITE problem awaits consent. */
	public record Result(boolean success, boolean needsConfirmation, List<Problem> problems,
			String message) {}

	private final List<ChangeProposal> proposals = new ArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Supplier<Program> program;
	private final DecompilerService decompiler;
	private Consumer<ChangeProposal> onApplied = p -> {};

	public ProposalManager(Supplier<Program> program, DecompilerService decompiler) {
		this.program = program;
		this.decompiler = decompiler;
	}

	public void setOnApplied(Consumer<ChangeProposal> c) {
		this.onApplied = c;
	}

	public void addListener(Runnable r) {
		listeners.add(r);
	}

	private void fire() {
		listeners.forEach(Runnable::run);
	}

	/** Adds a proposal; returns the existing pending one if an identical proposal is already queued. */
	public synchronized ChangeProposal add(ChangeProposal p) {
		for (ChangeProposal e : proposals) {
			if (e.status() == ChangeProposal.Status.PENDING && e.dedupeKey().equals(p.dedupeKey())) {
				return e;
			}
		}
		proposals.add(p);
		fire();
		return p;
	}

	public synchronized List<ChangeProposal> all() {
		return new ArrayList<>(proposals);
	}

	public synchronized List<ChangeProposal> pending() {
		return proposals.stream().filter(p -> p.status() == ChangeProposal.Status.PENDING).toList();
	}

	public synchronized ChangeProposal get(String id) {
		return proposals.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	/** Validation without applying; used to render the preview. */
	public List<Problem> validate(ChangeProposal p) {
		Program prog = program.get();
		if (prog == null || prog.isClosed()) {
			return List.of(Problem.error("No program is open."));
		}
		try {
			return p.validate(new ApplyContext(prog, decompiler, false));
		}
		catch (RuntimeException e) {
			return List.of(Problem.error("Validation failed: " + e.getMessage()));
		}
	}

	public void reject(String id) {
		synchronized (this) {
			ChangeProposal p = get(id);
			if (p != null && p.status() == ChangeProposal.Status.PENDING) {
				p.setStatus(ChangeProposal.Status.REJECTED, "Rejected by user");
			}
		}
		fire();
	}

	public void rejectAllPending() {
		pending().forEach(p -> reject(p.id()));
	}

	public synchronized void clearResolved() {
		proposals.removeIf(p -> p.status() != ChangeProposal.Status.PENDING);
		fire();
	}

	public synchronized void clearAll() {
		proposals.clear();
		fire();
	}

	/**
	 * Applies a proposal the user approved. Validation errors abort without touching the program.
	 * OVERWRITE problems require {@code allowOverwrite}; otherwise a confirmation request is returned.
	 */
	public Result approve(String id, boolean allowOverwrite) {
		ChangeProposal p;
		synchronized (this) {
			p = get(id);
		}
		if (p == null || p.status() != ChangeProposal.Status.PENDING) {
			return new Result(false, false, List.of(), "Proposal is not pending.");
		}
		Program prog = program.get();
		if (prog == null || prog.isClosed()) {
			return new Result(false, false, List.of(), "No program is open.");
		}
		ApplyContext ctx = new ApplyContext(prog, decompiler, allowOverwrite);
		List<Problem> problems;
		try {
			problems = p.validate(ctx);
		}
		catch (RuntimeException e) {
			return new Result(false, false, List.of(), "Validation failed: " + e.getMessage());
		}
		if (problems.stream().anyMatch(x -> x.severity() == Problem.Severity.ERROR)) {
			String msg = "Validation failed: " + problems.stream()
					.filter(x -> x.severity() == Problem.Severity.ERROR).map(Problem::message)
					.reduce((a, b) -> a + " " + b).orElse("");
			return new Result(false, false, problems, msg);
		}
		if (!allowOverwrite &&
			problems.stream().anyMatch(x -> x.severity() == Problem.Severity.OVERWRITE)) {
			return new Result(false, true, problems,
				"This would overwrite analyst-authored data. Confirm to proceed.");
		}
		int tx = prog.startTransaction("Local LLM: " + p.title());
		boolean ok = false;
		String error = null;
		try {
			p.apply(ctx);
			ok = true;
		}
		catch (ChangeException | RuntimeException e) {
			error = e.getMessage();
		}
		finally {
			prog.endTransaction(tx, ok);
		}
		if (ok) {
			decompiler.invalidate();
			p.setStatus(ChangeProposal.Status.APPLIED, "Applied");
			try {
				onApplied.accept(p);
			}
			catch (RuntimeException e) {
				// knowledge recording must never fail an applied change
			}
			fire();
			return new Result(true, false, problems, "Applied: " + p.title());
		}
		p.setStatus(ChangeProposal.Status.FAILED, error);
		fire();
		return new Result(false, false, problems, "Failed: " + error);
	}
}

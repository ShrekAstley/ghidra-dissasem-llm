package ghidrallm.tools;

import java.util.function.Supplier;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.log.DebugLog;
import ghidrallm.util.CancellationToken;

/** Everything a tool may touch. Tools get no other handle on Ghidra, the OS, or the network. */
public class ToolContext {
	private final Supplier<Program> program;
	private final Supplier<Address> currentAddress;
	public final DecompilerService decompiler;
	public final ProposalManager proposals;
	public final KnowledgeStore knowledge;
	public final Settings settings;
	public final DebugLog log;
	public final CancellationToken cancel;

	public ToolContext(Supplier<Program> program, Supplier<Address> currentAddress,
			DecompilerService decompiler, ProposalManager proposals, KnowledgeStore knowledge,
			Settings settings, DebugLog log, CancellationToken cancel) {
		this.program = program;
		this.currentAddress = currentAddress;
		this.decompiler = decompiler;
		this.proposals = proposals;
		this.knowledge = knowledge;
		this.settings = settings;
		this.log = log;
		this.cancel = cancel;
	}

	/** The active program, or a user-readable failure if none is open / it was closed. */
	public Program program() throws ToolException {
		Program p = program.get();
		if (p == null || p.isClosed()) {
			throw new ToolException("No program is open in Ghidra (or it was closed).");
		}
		return p;
	}

	public Address currentAddress() {
		return currentAddress == null ? null : currentAddress.get();
	}

	public ToolContext withCancel(CancellationToken t) {
		return new ToolContext(program, currentAddress, decompiler, proposals, knowledge, settings, log,
			t);
	}

	public void checkCancelled() {
		cancel.throwIfCancelled();
	}
}

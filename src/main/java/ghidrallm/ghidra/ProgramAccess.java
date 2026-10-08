package ghidrallm.ghidra;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;

/** What the assistant needs to know about the Ghidra tool's current state. */
public interface ProgramAccess {
	/** Active program or null. */
	Program program();

	/** Cursor address or null. */
	Address currentAddress();

	/** Human description of the current selection, or null. */
	default String selectionDescription() {
		return null;
	}
}

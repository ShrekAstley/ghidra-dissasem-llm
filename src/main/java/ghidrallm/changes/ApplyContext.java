package ghidrallm.changes;

import ghidra.program.model.listing.Program;
import ghidrallm.ghidra.DecompilerService;

/** What a proposal needs to validate/apply itself. {@code allowOverwrite} is the user's explicit consent. */
public record ApplyContext(Program program, DecompilerService decompiler, boolean allowOverwrite) {}

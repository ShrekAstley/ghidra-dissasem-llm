package ghidrallm.knowledge;

import ghidra.program.model.listing.Program;

public final class ProgramKeys {
	private ProgramKeys() {}

	/** Stable per-binary key: name plus executable hash when Ghidra recorded one. */
	public static String of(Program p) {
		String md5 = p.getExecutableMD5();
		return p.getName() + (md5 == null || md5.isBlank() ? "" : "|" + md5);
	}
}

package ghidrallm.packing;

import java.util.*;

/**
 * Scores static signals for packing or protection. Pure Java (no Ghidra types) so it can be unit tested with plain data;
 * {@code PackingTools} gathers the facts from the open program.
 */
public final class PackingAnalyzer {
	private PackingAnalyzer() {}

	public enum Verdict { NONE, SUSPECTED, LIKELY }

	/** One memory block as seen in the loaded image. {@code entropy} is bits per byte (0-8), or -1 if not measured. */
	public record Block(String name, boolean executable, boolean initialized, long size, double entropy) {}

	/** Everything the analyzer needs. {@code entryBlock} is the block holding the entry point, or null if unknown. */
	public record Facts(List<Block> blocks, String entryBlock, int importCount, int functionCount, long executableBytes) {}

	public record Report(Verdict verdict, String packer, List<String> evidence) {
		public boolean packed() {
			return verdict == Verdict.LIKELY;
		}

		public String format() {
			StringBuilder sb = new StringBuilder("Packing verdict: ").append(verdict.name());
			if (packer != null) {
				sb.append(" (").append(packer).append(')');
			}
			sb.append('\n');
			if (evidence.isEmpty()) {
				sb.append("Evidence: none of the static packing signals fired.\n");
			}
			else {
				sb.append("Evidence:\n");
				evidence.forEach(e -> sb.append("  - ").append(e).append('\n'));
			}
			if (verdict != Verdict.NONE) {
				sb.append("Impact: decompiler output, xrefs and imports are unreliable until the image is unpacked.\n");
				sb.append("Next: ").append(nextStep(packer)).append('\n');
			}
			return sb.toString();
		}
	}

	static final double HIGH_ENTROPY = 7.2;
	static final int FEW_IMPORTS = 10;

	/** Section-name prefixes (lower case) mapped to the packer they identify. */
	private static final Map<String, String> NAMED = new LinkedHashMap<>();
	static {
		NAMED.put(".themida", "Themida/WinLicense");
		NAMED.put(".winlice", "Themida/WinLicense");
		NAMED.put(".vmp", "VMProtect");
		NAMED.put("upx", "UPX");
		NAMED.put(".aspack", "ASPack");
		NAMED.put(".adata", "ASPack");
		NAMED.put(".petite", "Petite");
		NAMED.put(".mpress", "MPRESS");
		NAMED.put(".enigma", "Enigma Protector");
		NAMED.put(".nsp", "NsPack");
		NAMED.put(".packed", "unknown packer");
		NAMED.put(".perplex", "PESpin");
		NAMED.put(".yp", "Yoda's Protector");
	}

	public static Report analyze(Facts f) {
		List<String> ev = new ArrayList<>();
		String packer = null;
		int signals = 0;

		for (Block b : f.blocks()) {
			String lower = b.name().toLowerCase(Locale.ROOT);
			for (Map.Entry<String, String> e : NAMED.entrySet()) {
				if (lower.startsWith(e.getKey())) {
					ev.add("Section '" + b.name() + "' is a known " + e.getValue() + " section name.");
					packer = packer == null ? e.getValue() : packer;
					signals += 2;
					break;
				}
			}
		}

		for (Block b : f.blocks()) {
			if (b.initialized() && b.size() >= 4096 && b.entropy() >= HIGH_ENTROPY) {
				ev.add("Section '" + b.name() + "' has entropy " + String.format(Locale.ROOT, "%.2f", b.entropy())
						+ " bits/byte (" + (b.executable() ? "executable" : "data") + "), typical of compressed or encrypted data.");
				signals += b.executable() ? 2 : 1;
			}
			if (b.executable() && !b.initialized() && b.size() > 0) {
				ev.add("Executable section '" + b.name() + "' has no file contents (" + b.size()
						+ " bytes): code is written at run time.");
				signals += 2;
			}
		}

		if (f.entryBlock() != null) {
			Block entry = f.blocks().stream().filter(b -> b.name().equals(f.entryBlock())).findFirst().orElse(null);
			String n = f.entryBlock().toLowerCase(Locale.ROOT);
			if (entry != null && !n.equals(".text") && !n.equals("code") && !n.equals(".code") && !n.equals("text")
					&& !NAMED.keySet().stream().anyMatch(n::startsWith)) {
				ev.add("Entry point lies in '" + f.entryBlock() + "', not the main code section.");
				signals += 1;
			}
		}

		if (f.importCount() < FEW_IMPORTS && f.executableBytes() > 0) {
			ev.add("Only " + f.importCount() + " imports: the loader may resolve the real ones at run time.");
			signals += 1;
		}

		if (f.executableBytes() >= 64 * 1024 && f.functionCount() > 0 && f.executableBytes() / f.functionCount() > 4096) {
			ev.add("Only " + f.functionCount() + " functions over " + f.executableBytes()
					+ " executable bytes: most of the code did not disassemble.");
			signals += 1;
		}

		Verdict v = signals >= 3 ? Verdict.LIKELY : signals >= 1 ? Verdict.SUSPECTED : Verdict.NONE;
		return new Report(v, packer, ev);
	}

	static String nextStep(String packer) {
		if (packer != null && (packer.startsWith("UPX") || packer.startsWith("ASPack") || packer.startsWith("MPRESS"))) {
			return "this packer can usually be unpacked statically with its own tool; otherwise dump it at the original entry point.";
		}
		return "run the sample only in the isolated lab VM, break at the original entry point, dump the process, repair the import "
				+ "table, then analyze the dump.";
	}

	/** Shannon entropy in bits per byte. */
	public static double entropy(byte[] data, int len) {
		if (len <= 0) {
			return 0;
		}
		int[] counts = new int[256];
		for (int i = 0; i < len; i++) {
			counts[data[i] & 0xff]++;
		}
		double h = 0;
		for (int c : counts) {
			if (c > 0) {
				double p = (double) c / len;
				h -= p * (Math.log(p) / Math.log(2));
			}
		}
		return h;
	}
}

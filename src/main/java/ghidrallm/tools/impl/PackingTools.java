package ghidrallm.tools.impl;

import java.util.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Symbol;
import ghidrallm.packing.PackingAnalyzer;
import ghidrallm.packing.PackingAnalyzer.Block;
import ghidrallm.packing.PackingAnalyzer.Facts;
import ghidrallm.packing.PackingAnalyzer.Report;
import ghidrallm.tools.*;

/** Static packer / protector detection. Read-only: it never executes the program. */
public final class PackingTools {
	private PackingTools() {}

	/** Sample at most this many bytes per block when measuring entropy. */
	private static final int SAMPLE = 1 << 20;

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("detect_packing",
			"Check whether the program looks packed or protected (UPX, Themida, VMProtect, ...) from static signals: section names, entropy, imports, code coverage. Run this first on unfamiliar binaries; if the verdict is LIKELY, decompiler output is not trustworthy.",
			List.of(), (ctx, a) -> ToolResult.ok(analyze(ctx.program()).format())));
	}

	/** Verdict for a program; also used by {@code get_program_metadata}. */
	public static Report analyze(Program p) {
		List<Block> blocks = new ArrayList<>();
		long exec = 0;
		for (MemoryBlock b : p.getMemory().getBlocks()) {
			double ent = -1;
			if (b.isInitialized() && b.getSize() > 0) {
				int n = (int) Math.min(b.getSize(), SAMPLE);
				byte[] buf = new byte[n];
				try {
					b.getBytes(b.getStart(), buf);
					ent = PackingAnalyzer.entropy(buf, n);
				}
				catch (Exception e) {
					ent = -1;
				}
			}
			if (b.isExecute()) {
				exec += b.getSize();
			}
			blocks.add(new Block(b.getName(), b.isExecute(), b.isInitialized(), b.getSize(), ent));
		}
		String entryBlock = null;
		Address ep = firstEntry(p);
		if (ep != null) {
			MemoryBlock eb = p.getMemory().getBlock(ep);
			entryBlock = eb == null ? null : eb.getName();
		}
		int imports = 0;
		for (Symbol s : p.getSymbolTable().getExternalSymbols()) {
			if (s != null) {
				imports++;
			}
		}
		return PackingAnalyzer.analyze(new Facts(blocks, entryBlock, imports, p.getFunctionManager().getFunctionCount(), exec));
	}

	private static Address firstEntry(Program p) {
		var it = p.getSymbolTable().getExternalEntryPointIterator();
		return it.hasNext() ? it.next() : null;
	}
}

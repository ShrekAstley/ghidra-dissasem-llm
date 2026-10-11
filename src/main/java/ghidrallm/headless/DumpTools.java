package ghidrallm.headless;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Program;
import ghidrallm.packing.PackingAnalyzer;
import ghidrallm.tools.*;
import ghidrallm.tools.impl.PackingTools;

import static ghidrallm.tools.ToolParam.string;

/** {@code import_dump}: open an unpacked memory dump as the current program. Never executes anything. */
public final class DumpTools {
	private DumpTools() {}

	public static void register(ToolRegistry r, ProgramSession session, Path dumpsRoot) {
		r.register(new SimpleTool("import_dump", ToolPermission.LOAD_PROGRAM,
			"Open an unpacked memory dump (for example from Scylla or pe-sieve) from the dumps folder as the current program, replacing the packed one for all later tool calls. Optionally set the image base and the original entry point (OEP). Returns the packing verdict of the dump. Does not execute the file.",
			List.of(string("path", "Dump file name inside the dumps folder.", true),
				string("image_base", "Image base as hex (e.g. 0x10000000) if the dump header does not hold the right one.", false),
				string("oep", "Original entry point address as hex, to mark as the entry point and start disassembly there.", false)),
			(ctx, a) -> {
				Path file;
				try {
					file = DumpPaths.resolve(dumpsRoot, a.str("path"));
				}
				catch (IllegalArgumentException | java.io.IOException e) {
					throw new ToolException(e.getMessage());
				}
				Program p;
				try {
					p = session.open(new File(file.toString()));
				}
				catch (Exception e) {
					throw new ToolException("Could not import " + file.getFileName() + ": " + e.getMessage());
				}
				StringBuilder sb = new StringBuilder("Opened dump " + p.getName() + " (" + p.getLanguageID() + ") as the current program.\n");
				String base = a.str("image_base");
				if (base != null && !base.isBlank()) {
					setImageBase(p, base, sb);
				}
				String oep = a.str("oep");
				if (oep != null && !oep.isBlank()) {
					markEntry(p, oep, sb);
				}
				sb.append("Functions: ").append(p.getFunctionManager().getFunctionCount()).append('\n');
				PackingAnalyzer.Report rep = PackingTools.analyze(p);
				sb.append(rep.format());
				if (rep.packed()) {
					sb.append("The dump still looks packed or virtualized; the OEP or dump timing may be wrong.\n");
				}
				return ToolResult.ok(sb.toString());
			}));
	}

	private static void setImageBase(Program p, String text, StringBuilder sb) throws ToolException {
		int tx = p.startTransaction("set image base");
		boolean ok = false;
		try {
			Address a = p.getAddressFactory().getDefaultAddressSpace().getAddress(text.trim().replaceFirst("^0[xX]", ""));
			if (a == null) {
				throw new ToolException("Bad image_base: " + text);
			}
			p.setImageBase(a, true);
			sb.append("Image base set to ").append(a).append(".\n");
			ok = true;
		}
		catch (ToolException e) {
			throw e;
		}
		catch (Exception e) {
			throw new ToolException("Could not set image base: " + e.getMessage());
		}
		finally {
			p.endTransaction(tx, ok);
		}
	}

	private static void markEntry(Program p, String text, StringBuilder sb) throws ToolException {
		Address a;
		try {
			a = p.getAddressFactory().getDefaultAddressSpace().getAddress(text.trim().replaceFirst("^0[xX]", ""));
		}
		catch (ghidra.program.model.address.AddressFormatException e) {
			throw new ToolException("oep " + text + " is not a valid address.");
		}
		if (a == null || !p.getMemory().contains(a)) {
			throw new ToolException("oep " + text + " is not inside the dump's memory.");
		}
		int tx = p.startTransaction("mark OEP");
		try {
			p.getSymbolTable().addExternalEntryPoint(a);
			new DisassembleCommand(a, new AddressSet(a), true).applyTo(p);
			new CreateFunctionCmd(a).applyTo(p);
			sb.append("OEP ").append(a).append(" marked as the entry point and disassembled.\n");
		}
		finally {
			p.endTransaction(tx, true);
		}
	}
}

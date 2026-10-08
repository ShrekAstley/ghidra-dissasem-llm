package ghidrallm.tools.impl;

import java.util.*;
import java.util.regex.Pattern;

import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;
import ghidra.program.util.DefinedStringIterator;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/** Whole-program facts and broad search. */
public final class ProgramTools {
	private ProgramTools() {}

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("get_program_metadata",
			"Get program metadata: name, format, architecture, compiler, image base, hashes, and counts of functions, memory blocks and imports.",
			List.of(), (ctx, a) -> {
				Program p = ctx.program();
				StringBuilder sb = new StringBuilder();
				sb.append("Name: ").append(p.getName()).append('\n');
				sb.append("Format: ").append(p.getExecutableFormat()).append('\n');
				sb.append("Language: ").append(p.getLanguageID()).append(" (").append(p.getLanguage().getProcessor())
						.append(", ").append(p.getLanguage().isBigEndian() ? "big" : "little").append("-endian, ")
						.append(p.getDefaultPointerSize() * 8).append("-bit)\n");
				sb.append("Compiler spec: ").append(p.getCompilerSpec().getCompilerSpecID()).append('\n');
				sb.append("Image base: ").append(p.getImageBase()).append('\n');
				sb.append("Executable MD5: ").append(p.getExecutableMD5()).append('\n');
				int funcs = p.getFunctionManager().getFunctionCount();
				sb.append("Functions: ").append(funcs).append('\n');
				int ext = 0;
				for (String lib : p.getExternalManager().getExternalLibraryNames()) {
					ext++;
				}
				sb.append("External libraries: ").append(ext).append('\n');
				sb.append("Memory blocks:\n");
				for (MemoryBlock b : p.getMemory().getBlocks()) {
					sb.append("  ").append(b.getName()).append(' ').append(b.getStart()).append('-').append(b.getEnd())
							.append(' ').append(Fmt.perms(b)).append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("search_program",
			"Search across functions, symbols, defined strings and data types at once for a term. Good first step for natural-language questions.",
			List.of(string("query", "Term or regex.", true), integer("limit_per_category", "Max results per category.", false, 1, 25, 8L)),
			(ctx, a) -> {
				Program p = ctx.program();
				Pattern pat = FunctionTools.pattern(a.str("query"));
				int lim = a.integer("limit_per_category", 8);
				StringBuilder sb = new StringBuilder("Results for '" + a.str("query") + "':\n");

				sb.append("Functions:\n");
				int n = 0, scanned = 0;
				for (Function f : p.getFunctionManager().getFunctions(true)) {
					if (++scanned % 500 == 0) {
						ctx.checkCancelled();
					}
					if (pat.matcher(f.getName()).find() && n++ < lim) {
						sb.append("  ").append(Fmt.fn(f)).append('\n');
					}
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				else if (n > lim) {
					sb.append("  ... ").append(n - lim).append(" more\n");
				}

				sb.append("Symbols (non-function):\n");
				n = 0;
				scanned = 0;
				SymbolIterator si = p.getSymbolTable().getSymbolIterator(true);
				while (si.hasNext()) {
					Symbol s = si.next();
					if (++scanned % 5000 == 0) {
						ctx.checkCancelled();
					}
					if (s.getSymbolType() == SymbolType.FUNCTION || s.getSource() == SourceType.DEFAULT) {
						continue;
					}
					if (pat.matcher(s.getName()).find() && n++ < lim) {
						sb.append("  ").append(s.getName(true)).append(" @ ").append(s.getAddress()).append(s.isExternal() ? " [external]" : "").append('\n');
					}
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				else if (n > lim) {
					sb.append("  ... ").append(n - lim).append(" more\n");
				}

				sb.append("Strings:\n");
				n = 0;
				scanned = 0;
				for (Data d : DefinedStringIterator.forProgram(p)) {
					if (++scanned % 2000 == 0) {
						ctx.checkCancelled();
					}
					var inst = ghidra.program.model.data.StringDataInstance.getStringDataInstance(d);
					String v = inst == null ? null : inst.getStringValue();
					if (v != null && pat.matcher(v).find() && n++ < lim) {
						sb.append("  ").append(d.getAddress()).append(' ').append(Fmt.quote(v, 80)).append('\n');
					}
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				else if (n > lim) {
					sb.append("  ... ").append(n - lim).append(" more\n");
				}

				sb.append("Data types:\n");
				n = 0;
				Iterator<DataType> it = p.getDataTypeManager().getAllDataTypes();
				while (it.hasNext()) {
					DataType dt = it.next();
					if (pat.matcher(dt.getName()).find() && n++ < lim) {
						sb.append("  ").append(dt.getPathName()).append('\n');
					}
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("list_imports",
			"List imported (external) functions, optionally filtered by substring. Imports reveal capabilities (file I/O, networking, crypto...).",
			List.of(string("filter", "Optional substring of library or function name.", false),
				integer("limit", "Max results.", false, 1, 300, 80L)), (ctx, a) -> {
				Program p = ctx.program();
				String f = a.str("filter") == null ? null : a.str("filter").toLowerCase();
				int limit = a.integer("limit", 80);
				StringBuilder sb = new StringBuilder("Imports:\n");
				int n = 0;
				for (String lib : p.getExternalManager().getExternalLibraryNames()) {
					ExternalLocationIterator it = p.getExternalManager().getExternalLocations(lib);
					while (it.hasNext()) {
						ExternalLocation loc = it.next();
						String line = lib + "::" + loc.getLabel();
						if (f != null && !line.toLowerCase().contains(f)) {
							continue;
						}
						if (n++ >= limit) {
							sb.append("  ... more; use filter\n");
							return ToolResult.ok(sb.toString());
						}
						sb.append("  ").append(line).append('\n');
					}
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("list_functions",
			"Page through functions ordered by address, or rank by 'size' / 'xrefs' (most-called first) to find important functions.",
			List.of(enumeration("order", "address (default), size, or xrefs.", false, "address", "size", "xrefs"),
				bool("skip_thunks", "Skip thunks and external stubs.", false, true),
				integer("start", "Offset for paging.", false, 0, 10_000_000, 0L),
				integer("limit", "Max results.", false, 1, 100, 30L)), (ctx, a) -> {
				Program p = ctx.program();
				String order = a.str("order", "address");
				boolean skip = a.bool("skip_thunks", true);
				int start = a.integer("start", 0), limit = a.integer("limit", 30);
				List<Function> all = new ArrayList<>();
				for (Function f : p.getFunctionManager().getFunctions(true)) {
					if (!skip || (!f.isThunk() && !f.isExternal())) {
						all.add(f);
					}
				}
				if (order.equals("size")) {
					all.sort(Comparator.comparingLong((Function f) -> f.getBody().getNumAddresses()).reversed());
				}
				else if (order.equals("xrefs")) {
					all.sort(Comparator.comparingInt((Function f) -> p.getReferenceManager().getReferenceCountTo(f.getEntryPoint())).reversed());
				}
				StringBuilder sb = new StringBuilder("Functions (" + all.size() + " total, order=" + order + ", start=" + start + "):\n");
				for (int i = start; i < Math.min(all.size(), start + limit); i++) {
					Function f = all.get(i);
					sb.append("  ").append(Fmt.fn(f)).append("  size=").append(f.getBody().getNumAddresses())
							.append(" xrefs=").append(p.getReferenceManager().getReferenceCountTo(f.getEntryPoint())).append('\n');
				}
				if (start + limit < all.size()) {
					sb.append("... call again with start=").append(start + limit).append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));
	}
}

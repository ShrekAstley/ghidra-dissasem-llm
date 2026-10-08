package ghidrallm.tools.impl;

import java.util.*;
import java.util.regex.Pattern;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;
import ghidra.program.util.DefinedStringIterator;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/** Strings, symbols, globals, memory and data types. */
public final class DataTools {
	private DataTools() {}

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("get_string",
			"Read the string at an address (defined string data, or a printable C string in memory) and list where it is referenced.",
			List.of(string("address", "Address of the string or a symbol name.", true)), (ctx, a) -> {
				Program p = ctx.program();
				Address ad = Resolver.addressOrSymbol(p, a.str("address"));
				String s = Fmt.stringAt(p, ad);
				boolean defined = s != null;
				if (s == null) {
					s = readCString(p, ad, 256);
				}
				if (s == null) {
					return ToolResult.error("No string found at " + ad + ".");
				}
				StringBuilder sb = new StringBuilder("String at " + ad + (defined ? " (defined data)" : " (read from raw memory; not defined as a string in Ghidra)") + ":\n  " + Fmt.quote(s, 600) + "\n");
				sb.append("Referenced from:\n");
				ReferenceIterator it = p.getReferenceManager().getReferencesTo(ad);
				int n = 0;
				while (it.hasNext() && n < 12) {
					Reference ref = it.next();
					sb.append("  ").append(Fmt.label(p, ref.getFromAddress())).append('\n');
					n++;
				}
				if (n == 0) {
					sb.append("  (no references)\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("search_strings",
			"Search defined strings by substring/regex (case-insensitive). Returns address, value and reference count.",
			List.of(string("query", "Substring or regex.", true), integer("limit", "Max results.", false, 1, 100, 25L)),
			(ctx, a) -> {
				Program p = ctx.program();
				Pattern pat = FunctionTools.pattern(a.str("query"));
				int limit = a.integer("limit", 25);
				StringBuilder sb = new StringBuilder();
				int n = 0, scanned = 0;
				for (Data d : DefinedStringIterator.forProgram(p)) {
					if (++scanned % 2000 == 0) {
						ctx.checkCancelled();
					}
					StringDataInstance si = StringDataInstance.getStringDataInstance(d);
					String v = si == null ? null : si.getStringValue();
					if (v == null || !pat.matcher(v).find()) {
						continue;
					}
					if (n++ >= limit) {
						sb.append("  ... more; refine the query\n");
						break;
					}
					sb.append("  ").append(d.getAddress()).append("  ").append(Fmt.quote(v, 120)).append("  refs=")
							.append(p.getReferenceManager().getReferenceCountTo(d.getAddress())).append('\n');
				}
				return ToolResult.ok(n == 0 ? "No defined strings match '" + a.str("query") + "'." :
					"Strings matching '" + a.str("query") + "':\n" + sb);
			}));

		r.register(SimpleTool.read("search_symbols",
			"Search symbols (functions, labels, imports, globals) by substring; wildcards * and ? supported.",
			List.of(string("query", "Name or pattern.", true),
				enumeration("kind", "Filter: any (default), function, label, import.", false, "any", "function", "label", "import"),
				integer("limit", "Max results.", false, 1, 100, 30L)), (ctx, a) -> {
				Program p = ctx.program();
				String q = a.str("query");
				String glob = q.contains("*") || q.contains("?") ? q : "*" + q + "*";
				String kind = a.str("kind", "any");
				int limit = a.integer("limit", 30);
				StringBuilder sb = new StringBuilder();
				int n = 0, scanned = 0;
				SymbolIterator it = p.getSymbolTable().getSymbolIterator(glob, false);
				while (it.hasNext()) {
					Symbol s = it.next();
					if (++scanned % 2000 == 0) {
						ctx.checkCancelled();
					}
					boolean ext = s.isExternal();
					switch (kind) {
						case "function" -> {
							if (s.getSymbolType() != SymbolType.FUNCTION) {
								continue;
							}
						}
						case "label" -> {
							if (s.getSymbolType() != SymbolType.LABEL) {
								continue;
							}
						}
						case "import" -> {
							if (!ext) {
								continue;
							}
						}
						default -> {
						}
					}
					if (n++ >= limit) {
						sb.append("  ... more; refine the query\n");
						break;
					}
					sb.append("  ").append(s.getName(true)).append(" @ ").append(s.getAddress()).append("  [")
							.append(s.getSymbolType()).append(ext ? ", external" : "").append(", ").append(s.getSource()).append("]\n");
				}
				return ToolResult.ok(n == 0 ? "No symbols match '" + q + "'." : "Symbols matching '" + q + "':\n" + sb);
			}));

		r.register(SimpleTool.read("get_global",
			"Describe a global variable/data item: its type, size, current value representation, and the functions that read or write it.",
			List.of(string("target", "Symbol name or address.", true)), (ctx, a) -> {
				Program p = ctx.program();
				Address ad = Resolver.addressOrSymbol(p, a.str("target"));
				StringBuilder sb = new StringBuilder("Global " + Fmt.label(p, ad) + ":\n");
				Data d = p.getListing().getDataContaining(ad);
				if (d != null && d.isDefined()) {
					sb.append("  type: ").append(d.getDataType().getName()).append(", size ").append(d.getLength()).append('\n');
					sb.append("  value: ").append(ghidrallm.util.Text.truncate(d.getDefaultValueRepresentation(), 200)).append('\n');
				}
				else {
					sb.append("  (no defined data type at this address)\n");
				}
				Map<String, Set<String>> users = new TreeMap<>();
				ReferenceIterator it = p.getReferenceManager().getReferencesTo(ad);
				int total = 0;
				while (it.hasNext()) {
					Reference ref = it.next();
					total++;
					Function f = p.getFunctionManager().getFunctionContaining(ref.getFromAddress());
					String kind = ref.getReferenceType().isWrite() ? "writes" : ref.getReferenceType().isRead() ? "reads" : "refs";
					users.computeIfAbsent(kind, k -> new LinkedHashSet<>())
							.add(f != null ? f.getName() + "@" + f.getEntryPoint() : ref.getFromAddress().toString());
				}
				sb.append("  references: ").append(total).append('\n');
				for (var e : users.entrySet()) {
					sb.append("  ").append(e.getKey()).append(": ")
							.append(e.getValue().stream().limit(10).toList()).append(e.getValue().size() > 10 ? " ..." : "").append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_memory",
			"Describe the memory map (blocks, permissions) or, with an address, the block/code unit it belongs to.",
			List.of(string("address", "Optional address to look up.", false)), (ctx, a) -> {
				Program p = ctx.program();
				Memory m = p.getMemory();
				StringBuilder sb = new StringBuilder();
				if (a.str("address") != null) {
					Address ad = Resolver.addressOrSymbol(p, a.str("address"));
					MemoryBlock b = m.getBlock(ad);
					if (b == null) {
						return ToolResult.error(ad + " is not in any memory block.");
					}
					sb.append(ad).append(" is in block '").append(b.getName()).append("' ").append(b.getStart()).append('-').append(b.getEnd())
							.append(" perms ").append(Fmt.perms(b)).append(b.isInitialized() ? "" : " (uninitialized)").append('\n');
					CodeUnit cu = p.getListing().getCodeUnitContaining(ad);
					if (cu != null) {
						sb.append("Code unit: ").append(cu instanceof Instruction ? "instruction " : "data ").append(cu).append('\n');
					}
					return ToolResult.ok(sb.toString());
				}
				sb.append("Memory blocks:\n");
				for (MemoryBlock b : m.getBlocks()) {
					sb.append("  ").append(b.getName()).append("  ").append(b.getStart()).append('-').append(b.getEnd())
							.append("  size ").append(Fmt.hex(b.getSize())).append("  ").append(Fmt.perms(b))
							.append(b.isInitialized() ? "" : "  uninitialized").append(b.isExternalBlock() ? "  external" : "").append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("read_memory",
			"Hex-dump bytes from program memory (static file contents, not a running process). Max 512 bytes.",
			List.of(string("address", "Start address or symbol.", true),
				integer("length", "Bytes to read.", false, 1, 512, 64L)), (ctx, a) -> {
				Program p = ctx.program();
				Address ad = Resolver.addressOrSymbol(p, a.str("address"));
				int len = a.integer("length", 64);
				byte[] buf = new byte[len];
				int got;
				try {
					got = p.getMemory().getBytes(ad, buf);
				}
				catch (MemoryAccessException e) {
					return ToolResult.error("Cannot read memory at " + ad + ": " + e.getMessage());
				}
				StringBuilder sb = new StringBuilder("Memory at " + ad + " (" + got + " bytes):\n");
				for (int i = 0; i < got; i += 16) {
					sb.append(String.format("  %s  ", ad.add(i)));
					StringBuilder asc = new StringBuilder();
					for (int j = 0; j < 16; j++) {
						if (i + j < got) {
							int b = buf[i + j] & 0xff;
							sb.append(String.format("%02x ", b));
							asc.append(b >= 32 && b < 127 ? (char) b : '.');
						}
						else {
							sb.append("   ");
						}
					}
					sb.append(" |").append(asc).append("|\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_instruction_at_address",
			"Show the instruction(s) at an address with bytes, flow type, operand references and comments.",
			List.of(string("address", "Instruction address.", true),
				integer("count", "How many consecutive instructions.", false, 1, 20, 1L)), (ctx, a) -> {
				Program p = ctx.program();
				Address ad = Resolver.address(p, a.str("address"));
				Instruction ins = p.getListing().getInstructionContaining(ad);
				if (ins == null) {
					Data d = p.getListing().getDataContaining(ad);
					return ToolResult.ok("No instruction at " + ad + (d != null ? "; data: " + d.getDataType().getName() + " = " + d.getDefaultValueRepresentation() : "") + ".");
				}
				StringBuilder sb = new StringBuilder();
				int count = a.integer("count", 1);
				for (int i = 0; i < count && ins != null; i++, ins = ins.getNext()) {
					sb.append(Fmt.instruction(p, ins)).append("   bytes=");
					try {
						for (byte b : ins.getBytes()) {
							sb.append(String.format("%02x", b));
						}
					}
					catch (MemoryAccessException e) {
						sb.append("?");
					}
					sb.append("  flow=").append(ins.getFlowType()).append('\n');
				}
				Function f = p.getFunctionManager().getFunctionContaining(ad);
				if (f != null) {
					sb.append("(in ").append(Fmt.fn(f)).append(")\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_data_type",
			"Show a data type's definition (struct layout, enum values, typedef, function definition) by name.",
			List.of(string("name", "Data type name (without path), e.g. FILE or MY_STRUCT.", true)), (ctx, a) -> {
				DataTypeManager dtm = ctx.program().getDataTypeManager();
				List<DataType> found = new ArrayList<>();
				dtm.findDataTypes(a.str("name"), found);
				if (found.isEmpty()) {
					BuiltInDataTypeManager.getDataTypeManager().findDataTypes(a.str("name"), found);
				}
				if (found.isEmpty()) {
					return ToolResult.error("No data type named '" + a.str("name") + "'. Try search_data_types.");
				}
				StringBuilder sb = new StringBuilder();
				for (DataType dt : found.stream().limit(3).toList()) {
					sb.append(dt.getPathName()).append("  (size ").append(dt.getLength()).append(")\n");
					if (dt instanceof Composite c) {
						sb.append(c instanceof Structure ? "struct" : "union").append(" {\n");
						int shown = 0;
						for (DataTypeComponent comp : c.getDefinedComponents()) {
							if (shown++ >= 80) {
								sb.append("  ...\n");
								break;
							}
							sb.append(String.format("  +0x%x  %s %s%s%n", comp.getOffset(), comp.getDataType().getName(),
								comp.getFieldName() == null ? "(unnamed)" : comp.getFieldName(),
								comp.getComment() == null ? "" : "  // " + comp.getComment()));
						}
						sb.append("}\n");
					}
					else if (dt instanceof ghidra.program.model.data.Enum e) {
						for (String n : e.getNames()) {
							sb.append("  ").append(n).append(" = ").append(Fmt.hex(e.getValue(n))).append('\n');
						}
					}
					else if (dt instanceof TypeDef td) {
						sb.append("  typedef of ").append(td.getBaseDataType().getName()).append('\n');
					}
					else if (dt instanceof FunctionDefinition fd) {
						sb.append("  ").append(fd.getPrototypeString()).append('\n');
					}
				}
				if (found.size() > 3) {
					sb.append("(").append(found.size() - 3).append(" more types with this name)\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("search_data_types",
			"Search the program's data types by name substring/regex.",
			List.of(string("query", "Name pattern.", true), integer("limit", "Max results.", false, 1, 100, 25L)),
			(ctx, a) -> {
				Pattern pat = FunctionTools.pattern(a.str("query"));
				int limit = a.integer("limit", 25);
				StringBuilder sb = new StringBuilder();
				int n = 0;
				List<Iterator<DataType>> sources = List.of(ctx.program().getDataTypeManager().getAllDataTypes(),
					BuiltInDataTypeManager.getDataTypeManager().getAllDataTypes());
				Set<String> seenPaths = new HashSet<>();
				for (Iterator<DataType> it : sources) {
					while (it.hasNext()) {
					DataType dt = it.next();
					if (!pat.matcher(dt.getName()).find() || !seenPaths.add(dt.getPathName())) {
						continue;
					}
					if (n++ >= limit) {
						sb.append("  ... more; refine the query\n");
						break;
					}
					sb.append("  ").append(dt.getPathName()).append("  size ").append(dt.getLength()).append('\n');
					}
					if (n > limit) {
						break;
					}
				}
				return ToolResult.ok(n == 0 ? "No data types match '" + a.str("query") + "'." : "Data types:\n" + sb);
			}));
	}

	/** Best-effort printable C string read; returns null if the bytes do not look like text. */
	static String readCString(Program p, Address ad, int max) {
		StringBuilder sb = new StringBuilder();
		try {
			for (int i = 0; i < max; i++) {
				int b = p.getMemory().getByte(ad.add(i)) & 0xff;
				if (b == 0) {
					break;
				}
				if (b < 9 || (b > 13 && b < 32) || b > 126) {
					return sb.length() >= 4 ? sb.toString() : null;
				}
				sb.append((char) b);
			}
		}
		catch (MemoryAccessException | ghidra.program.model.address.AddressOutOfBoundsException e) {
			return sb.length() >= 4 ? sb.toString() : null;
		}
		return sb.length() >= 3 ? sb.toString() : null;
	}
}

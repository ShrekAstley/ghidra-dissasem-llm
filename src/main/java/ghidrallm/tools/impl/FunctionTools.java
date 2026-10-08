package ghidrallm.tools.impl;

import java.util.*;
import java.util.regex.Pattern;

import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.symbol.*;
import ghidra.util.task.TaskMonitor;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/** Function-centric inspection tools. */
public final class FunctionTools {
	private FunctionTools() {}

	static final ToolParam FUNC = string("function",
		"Function name, address (e.g. 0x401000), or 'current' for the function selected in Ghidra.", false);

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("get_current_function",
			"Describe the function containing the user's current cursor location in Ghidra, with its signature, size and call relationships.",
			List.of(), (ctx, a) -> ToolResult.ok(describe(ctx, Resolver.function(ctx, "current"), true))));

		r.register(SimpleTool.read("get_function",
			"Get an overview of a function: name, address range, signature, size, caller/callee counts and comments.",
			List.of(FUNC), (ctx, a) -> ToolResult.ok(describe(ctx, Resolver.function(ctx, a.str("function")), false))));

		r.register(SimpleTool.read("get_function_signature",
			"Get a function's prototype, calling convention, parameters (with storage) and return type.",
			List.of(FUNC), (ctx, a) -> ToolResult.ok(signature(Resolver.function(ctx, a.str("function"))))));

		r.register(SimpleTool.read("get_function_decompile",
			"Get Ghidra's decompiled C for a function. NOTE: decompiler output can be inaccurate (types, variable merging, missed control flow); cross-check against the assembly.",
			List.of(FUNC), (ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				ctx.checkCancelled();
				String c = ctx.decompiler.decompiledC(f, TaskMonitor.DUMMY);
				return ToolResult.ok("Decompiler output for " + Fmt.fn(f) +
					" (may be inaccurate; verify against assembly):\n" + c);
			}));

		r.register(SimpleTool.read("get_function_assembly",
			"Get disassembly of a function with symbolic annotations for calls, strings and globals.",
			List.of(FUNC, integer("start", "Skip this many instructions first (paging).", false, 0, 1_000_000, 0L),
				integer("max_instructions", "Maximum instructions to return.", false, 1, 2000, 250L)),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				int skip = a.integer("start", 0), max = a.integer("max_instructions", 250);
				StringBuilder sb = new StringBuilder("Assembly for " + Fmt.fn(f) + ":\n");
				int i = 0, shown = 0;
				boolean more = false;
				for (Instruction ins : ctx.program().getListing().getInstructions(f.getBody(), true)) {
					if (i++ < skip) {
						continue;
					}
					if (shown >= max) {
						more = true;
						break;
					}
					Symbol s = ctx.program().getSymbolTable().getPrimarySymbol(ins.getAddress());
					if (s != null && s.getSource() != SourceType.DEFAULT && s.getSymbolType() != SymbolType.FUNCTION) {
						sb.append(s.getName()).append(":\n");
					}
					sb.append("  ").append(Fmt.instruction(ctx.program(), ins)).append('\n');
					shown++;
					if (shown % 200 == 0) {
						ctx.checkCancelled();
					}
				}
				if (more) {
					sb.append("... more instructions; call again with start=").append(skip + shown).append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_function_variables",
			"List a function's parameters and local variables, using the exact names shown in the decompiler (use these names for propose_rename_variable).",
			List.of(FUNC), (ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				StringBuilder sb = new StringBuilder("Variables of " + Fmt.fn(f) + ":\n");
				DecompileResults dr = ctx.decompiler.decompile(f, TaskMonitor.DUMMY);
				if (dr.decompileCompleted() && dr.getHighFunction() != null) {
					HighFunction hf = dr.getHighFunction();
					Iterator<HighSymbol> it = hf.getLocalSymbolMap().getSymbols();
					int n = 0;
					while (it.hasNext() && n < 120) {
						HighSymbol s = it.next();
						sb.append("  ").append(s.isParameter() ? "param " : "local ")
								.append(s.getName()).append(" : ").append(s.getDataType().getName())
								.append("  [size ").append(s.getSize()).append("]")
								.append(s.isNameLocked() ? "  (analyst-named)" : "").append('\n');
						n++;
					}
					if (n == 0) {
						sb.append("  (none)\n");
					}
				}
				else {
					sb.append("  decompiler unavailable; database variables:\n");
					for (Variable v : f.getAllVariables()) {
						sb.append("  ").append(v.getName()).append(" : ").append(v.getDataType().getName())
								.append("  @ ").append(v.getVariableStorage()).append('\n');
					}
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_function_at_address",
			"Find the function containing an address (or report that none does).",
			List.of(string("address", "Address, e.g. 0x401234.", true)), (ctx, a) -> {
				Address ad = Resolver.address(ctx.program(), a.str("address"));
				Function f = ctx.program().getFunctionManager().getFunctionContaining(ad);
				if (f == null) {
					return ToolResult.ok("No function contains " + ad + ". Nearest label: " + Fmt.label(ctx.program(), ad));
				}
				long off = ad.subtract(f.getEntryPoint());
				return ToolResult.ok(ad + " is inside " + Fmt.fn(f) + " at offset +0x" + Long.toHexString(off) + "\n" +
					f.getPrototypeString(false, false));
			}));

		r.register(SimpleTool.read("get_callers",
			"List functions that call the given function, with call-site addresses.",
			List.of(FUNC, integer("limit", "Max results.", false, 1, 200, 40L)), (ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				int limit = a.integer("limit", 40);
				Map<Function, List<Address>> sites = new LinkedHashMap<>();
				List<Address> unknown = new ArrayList<>();
				ReferenceIterator it = ctx.program().getReferenceManager().getReferencesTo(f.getEntryPoint());
				while (it.hasNext()) {
					Reference ref = it.next();
					if (!ref.getReferenceType().isCall() && !ref.getReferenceType().isJump()) {
						continue;
					}
					Function c = ctx.program().getFunctionManager().getFunctionContaining(ref.getFromAddress());
					if (c == null) {
						unknown.add(ref.getFromAddress());
					}
					else {
						sites.computeIfAbsent(c, k -> new ArrayList<>()).add(ref.getFromAddress());
					}
				}
				StringBuilder sb = new StringBuilder("Callers of " + Fmt.fn(f) + " (" + sites.size() + " functions):\n");
				int n = 0;
				for (var e : sites.entrySet()) {
					if (n++ >= limit) {
						sb.append("  ... ").append(sites.size() - limit).append(" more\n");
						break;
					}
					sb.append("  ").append(Fmt.fn(e.getKey())).append("  call sites: ")
							.append(e.getValue().stream().limit(4).map(Address::toString).toList());
					if (e.getValue().size() > 4) {
						sb.append(" +").append(e.getValue().size() - 4);
					}
					sb.append('\n');
				}
				if (!unknown.isEmpty()) {
					sb.append("  (+").append(unknown.size()).append(" references from outside any function, e.g. data/pointers: ")
							.append(unknown.stream().limit(3).map(Address::toString).toList()).append(")\n");
				}
				if (sites.isEmpty() && unknown.isEmpty()) {
					sb.append("  none found (may be called indirectly or be an entry point)\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_callees",
			"List functions called by the given function (direct calls only; indirect calls are listed separately).",
			List.of(FUNC, integer("limit", "Max results.", false, 1, 200, 60L)), (ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				int limit = a.integer("limit", 60);
				Map<String, Integer> counts = new LinkedHashMap<>();
				int indirect = 0;
				for (Instruction ins : ctx.program().getListing().getInstructions(f.getBody(), true)) {
					if (!ins.getFlowType().isCall()) {
						continue;
					}
					boolean any = false;
					for (Reference ref : ins.getReferencesFrom()) {
						if (!ref.getReferenceType().isCall()) {
							continue;
						}
						Function t = ctx.program().getFunctionManager().getFunctionAt(ref.getToAddress());
						String name = t != null ? Fmt.fn(t) : Fmt.label(ctx.program(), ref.getToAddress());
						counts.merge(name, 1, Integer::sum);
						any = true;
					}
					if (!any) {
						indirect++;
					}
				}
				StringBuilder sb = new StringBuilder("Callees of " + Fmt.fn(f) + " (" + counts.size() + " distinct):\n");
				int n = 0;
				for (var e : counts.entrySet()) {
					if (n++ >= limit) {
						sb.append("  ... more\n");
						break;
					}
					sb.append("  ").append(e.getKey()).append(e.getValue() > 1 ? "  x" + e.getValue() : "").append('\n');
				}
				if (indirect > 0) {
					sb.append("  (+").append(indirect).append(" indirect/unresolved calls, e.g. via function pointers or vtables)\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_call_graph",
			"Show the call graph around a function as an indented tree (bounded depth and size).",
			List.of(FUNC, enumeration("direction", "callees (default) or callers.", false, "callees", "callers"),
				integer("depth", "Tree depth.", false, 1, 4, 2L)), (ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				boolean down = !"callers".equals(a.str("direction", "callees"));
				int depth = a.integer("depth", 2);
				StringBuilder sb = new StringBuilder((down ? "Callees" : "Callers") + " tree of " + Fmt.fn(f) + ":\n");
				int[] budget = {80};
				walk(ctx, f, down, depth, 0, new HashSet<>(), sb, budget);
				if (budget[0] <= 0) {
					sb.append("... (truncated at 80 nodes)\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("search_functions",
			"Search functions by name substring or regex (case-insensitive). Optionally filter by minimum instruction count.",
			List.of(string("query", "Substring or regex matched against function names.", true),
				integer("min_instructions", "Only functions with at least this many instructions.", false, 0, 1_000_000, 0L),
				integer("limit", "Max results.", false, 1, 100, 25L)), (ctx, a) -> {
				Pattern pat = pattern(a.str("query"));
				int limit = a.integer("limit", 25), min = a.integer("min_instructions", 0);
				StringBuilder sb = new StringBuilder();
				int n = 0, scanned = 0;
				for (Function f : ctx.program().getFunctionManager().getFunctions(true)) {
					if (++scanned % 500 == 0) {
						ctx.checkCancelled();
					}
					if (!pat.matcher(f.getName()).find()) {
						continue;
					}
					int ic = min > 0 ? Fmt.instructionCount(f) : -1;
					if (min > 0 && ic < min) {
						continue;
					}
					if (n++ >= limit) {
						sb.append("... more matches; refine the query\n");
						break;
					}
					sb.append("  ").append(Fmt.fn(f)).append(f.isThunk() ? " [thunk]" : "").append(f.isExternal() ? " [external]" : "").append('\n');
				}
				return ToolResult.ok(n == 0 ? "No functions match '" + a.str("query") + "'." :
					"Functions matching '" + a.str("query") + "':\n" + sb);
			}));

		r.register(SimpleTool.read("get_entry_points",
			"List program entry points and conventional start functions (entry, main, WinMain, DllMain, _start, ...).",
			List.of(), (ctx, a) -> {
				Program p = ctx.program();
				StringBuilder sb = new StringBuilder("Entry points:\n");
				Set<Address> seen = new LinkedHashSet<>();
				var it = p.getSymbolTable().getExternalEntryPointIterator();
				while (it.hasNext()) {
					Address ad = it.next();
					seen.add(ad);
					sb.append("  external entry: ").append(Fmt.label(p, ad)).append('\n');
				}
				for (String name : List.of("entry", "_start", "start", "main", "_main", "WinMain", "wWinMain",
					"DllMain", "_DllMainCRTStartup", "mainCRTStartup", "WinMainCRTStartup", "__libc_start_main", "DriverEntry")) {
					for (Function f : Resolver.functionsNamed(p, name)) {
						if (seen.add(f.getEntryPoint())) {
							sb.append("  function: ").append(Fmt.fn(f)).append('\n');
						}
					}
				}
				if (seen.isEmpty()) {
					sb.append("  none identified; use search_functions or get_program_metadata\n");
				}
				return ToolResult.ok(sb.toString());
			}));
	}

	static Pattern pattern(String q) throws ToolException {
		try {
			return Pattern.compile(q, Pattern.CASE_INSENSITIVE);
		}
		catch (java.util.regex.PatternSyntaxException e) {
			return Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE);
		}
	}

	private static void walk(ToolContext ctx, Function f, boolean down, int depth, int level,
			Set<Address> visited, StringBuilder sb, int[] budget) {
		if (depth == 0 || budget[0] <= 0) {
			return;
		}
		Set<Function> next = down ? Fmt.callees(f) : Fmt.callers(f);
		List<Function> sorted = new ArrayList<>(next);
		sorted.sort(Comparator.comparing(Function::getEntryPoint));
		for (Function c : sorted) {
			if (budget[0]-- <= 0) {
				return;
			}
			boolean seen = !visited.add(c.getEntryPoint());
			sb.append("  ".repeat(level + 1)).append(down ? "-> " : "<- ").append(Fmt.fn(c)).append(seen ? " (seen)" : "").append('\n');
			if (!seen) {
				walk(ctx, c, down, depth - 1, level + 1, visited, sb, budget);
			}
		}
	}

	static String signature(Function f) {
		StringBuilder sb = new StringBuilder();
		sb.append("Function: ").append(f.getName(true)).append(" @ ").append(f.getEntryPoint()).append('\n');
		sb.append("Prototype: ").append(f.getPrototypeString(true, true)).append('\n');
		sb.append("Calling convention: ").append(f.getCallingConventionName()).append('\n');
		sb.append("Return type: ").append(f.getReturn().getDataType().getName()).append('\n');
		sb.append("Signature source: ").append(f.getSignatureSource()).append(f.hasCustomVariableStorage() ? " (custom storage)" : "").append('\n');
		sb.append("Parameters:\n");
		if (f.getParameterCount() == 0) {
			sb.append("  (none recorded)\n");
		}
		for (Parameter p : f.getParameters()) {
			sb.append("  ").append(p.getOrdinal()).append(": ").append(p.getDataType().getName()).append(' ')
					.append(p.getName()).append("  @ ").append(p.getVariableStorage()).append('\n');
		}
		sb.append("Flags:").append(f.hasNoReturn() ? " noreturn" : "").append(f.isInline() ? " inline" : "")
				.append(f.isThunk() ? " thunk" : "").append(f.hasVarArgs() ? " varargs" : "").append('\n');
		return sb.toString();
	}

	static String describe(ToolContext ctx, Function f, boolean withCursor) throws ToolException {
		Program p = ctx.program();
		StringBuilder sb = new StringBuilder();
		if (withCursor) {
			Address cur = ctx.currentAddress();
			sb.append("Cursor: ").append(cur).append('\n');
		}
		sb.append("Name: ").append(f.getName(true)).append(f.getSymbol().getSource() == SourceType.USER_DEFINED ? "  (analyst-named)" : "").append('\n');
		sb.append("Entry: ").append(f.getEntryPoint()).append('\n');
		AddressSet body = new AddressSet(f.getBody());
		sb.append("Range: ").append(body.getMinAddress()).append(" - ").append(body.getMaxAddress())
				.append("  (").append(body.getNumAddresses()).append(" bytes, ").append(Fmt.instructionCount(f)).append(" instructions)\n");
		sb.append("Prototype: ").append(f.getPrototypeString(false, false)).append('\n');
		sb.append("Calling convention: ").append(f.getCallingConventionName()).append('\n');
		sb.append("Callers: ").append(Fmt.callers(f).size()).append(", callees: ").append(Fmt.callees(f).size()).append('\n');
		sb.append("Flags:").append(f.isThunk() ? " thunk" : "").append(f.hasNoReturn() ? " noreturn" : "")
				.append(f.isExternal() ? " external" : "").append('\n');
		String c = f.getComment();
		if (c != null) {
			sb.append("Function comment: ").append(c).append('\n');
		}
		String plate = p.getListing().getComment(CommentType.PLATE, f.getEntryPoint());
		if (plate != null) {
			sb.append("Plate comment: ").append(plate).append('\n');
		}
		return sb.toString();
	}
}

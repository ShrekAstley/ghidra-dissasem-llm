package ghidrallm.tools.impl;

import java.util.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/** Cross-reference tools. */
public final class ReferenceTools {
	private ReferenceTools() {}

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("get_xrefs_to",
			"List cross-references TO an address or symbol (who calls/reads/writes/points at it).",
			List.of(string("target", "Address, function name, or symbol name.", true),
				integer("limit", "Max results.", false, 1, 200, 40L)), (ctx, a) -> {
				Program p = ctx.program();
				Address to = Resolver.addressOrSymbol(p, a.str("target"));
				int limit = a.integer("limit", 40);
				StringBuilder sb = new StringBuilder("References to " + Fmt.label(p, to) + " (" +
					p.getReferenceManager().getReferenceCountTo(to) + " total):\n");
				ReferenceIterator it = p.getReferenceManager().getReferencesTo(to);
				int n = 0;
				while (it.hasNext()) {
					Reference ref = it.next();
					if (n++ >= limit) {
						sb.append("  ... more\n");
						break;
					}
					sb.append("  ").append(Fmt.label(p, ref.getFromAddress())).append("  [")
							.append(Fmt.refType(ref)).append("]");
					Instruction ins = p.getListing().getInstructionAt(ref.getFromAddress());
					if (ins != null) {
						sb.append("  ").append(ins);
					}
					sb.append('\n');
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_xrefs_from",
			"List references FROM an instruction address, or (function_wide=true) from every instruction of the function containing it.",
			List.of(string("source", "Instruction address, or function name/address with function_wide.", true),
				bool("function_wide", "Collect outgoing references for the whole function.", false, false),
				integer("limit", "Max results.", false, 1, 300, 60L)), (ctx, a) -> {
				Program p = ctx.program();
				int limit = a.integer("limit", 60);
				StringBuilder sb = new StringBuilder();
				if (a.bool("function_wide", false)) {
					Function f = Resolver.function(ctx, a.str("source"));
					sb.append("Outgoing references of ").append(Fmt.fn(f)).append(":\n");
					AddressIterator it = p.getReferenceManager().getReferenceSourceIterator(f.getBody(), true);
					int n = 0;
					while (it.hasNext()) {
						Address from = it.next();
						for (Reference ref : p.getReferenceManager().getReferencesFrom(from)) {
							if (ref.getReferenceType().isFallthrough() || ref.isStackReference() || ref.isRegisterReference()) {
								continue;
							}
							if (n++ >= limit) {
								sb.append("  ... more\n");
								return ToolResult.ok(sb.toString());
							}
							sb.append("  ").append(from).append(" [").append(Fmt.refType(ref)).append("] -> ")
									.append(Fmt.label(p, ref.getToAddress())).append('\n');
						}
					}
					return ToolResult.ok(sb.toString());
				}
				Address from = Resolver.addressOrSymbol(p, a.str("source"));
				sb.append("References from ").append(from).append(":\n");
				int n = 0;
				for (Reference ref : p.getReferenceManager().getReferencesFrom(from)) {
					if (n++ >= limit) {
						break;
					}
					sb.append("  [").append(Fmt.refType(ref)).append("] -> ").append(Fmt.label(p, ref.getToAddress()));
					String s = Fmt.stringAt(p, ref.getToAddress());
					if (s != null) {
						sb.append("  string ").append(Fmt.quote(s, 80));
					}
					sb.append('\n');
				}
				if (n == 0) {
					sb.append("  none\n");
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_referenced_strings",
			"List string literals referenced by a function (deterministic: derived from Ghidra references).",
			List.of(FunctionTools.FUNC, integer("limit", "Max results.", false, 1, 200, 40L)), (ctx, a) -> {
				Program p = ctx.program();
				Function f = Resolver.function(ctx, a.str("function"));
				int limit = a.integer("limit", 40);
				Map<Address, String> found = new LinkedHashMap<>();
				for (Address from : iterable(p.getReferenceManager().getReferenceSourceIterator(f.getBody(), true))) {
					for (Reference ref : p.getReferenceManager().getReferencesFrom(from)) {
						if (ref.getReferenceType().isFlow()) {
							continue;
						}
						String s = Fmt.stringAt(p, ref.getToAddress());
						if (s != null) {
							found.putIfAbsent(ref.getToAddress(), s);
						}
					}
				}
				StringBuilder sb = new StringBuilder("Strings referenced by " + Fmt.fn(f) + " (" + found.size() + "):\n");
				int n = 0;
				for (var e : found.entrySet()) {
					if (n++ >= limit) {
						sb.append("  ... more\n");
						break;
					}
					sb.append("  ").append(e.getKey()).append("  ").append(Fmt.quote(e.getValue(), 160)).append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));

		r.register(SimpleTool.read("get_referenced_globals",
			"List global data (non-string) referenced by a function, with read/write direction.",
			List.of(FunctionTools.FUNC, integer("limit", "Max results.", false, 1, 200, 40L)), (ctx, a) -> {
				Program p = ctx.program();
				Function f = Resolver.function(ctx, a.str("function"));
				int limit = a.integer("limit", 40);
				Map<Address, String> dirs = new LinkedHashMap<>();
				for (Address from : iterable(p.getReferenceManager().getReferenceSourceIterator(f.getBody(), true))) {
					for (Reference ref : p.getReferenceManager().getReferencesFrom(from)) {
						Address to = ref.getToAddress();
						if (ref.getReferenceType().isFlow() || !ref.getReferenceType().isData() ||
							to.isExternalAddress() || ref.isStackReference() || ref.isRegisterReference()) {
							continue;
						}
						if (Fmt.stringAt(p, to) != null) {
							continue;
						}
						String d = ref.getReferenceType().isWrite() && ref.getReferenceType().isRead() ? "R/W"
								: ref.getReferenceType().isWrite() ? "W" : ref.getReferenceType().isRead() ? "R" : "ptr";
						dirs.merge(to, d, (x, y) -> x.equals(y) ? x : "R/W");
					}
				}
				StringBuilder sb = new StringBuilder("Globals referenced by " + Fmt.fn(f) + " (" + dirs.size() + "):\n");
				int n = 0;
				for (var e : dirs.entrySet()) {
					if (n++ >= limit) {
						sb.append("  ... more\n");
						break;
					}
					Data d = p.getListing().getDataContaining(e.getKey());
					sb.append("  ").append(Fmt.label(p, e.getKey())).append("  ").append(e.getValue())
							.append(d != null ? "  type " + d.getDataType().getName() : "").append('\n');
				}
				return ToolResult.ok(sb.toString());
			}));
	}

	static Iterable<Address> iterable(AddressIterator it) {
		return () -> it;
	}
}

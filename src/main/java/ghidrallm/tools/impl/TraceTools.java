package ghidrallm.tools.impl;

import java.util.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.TaskMonitor;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/**
 * Value / data-flow investigation over the decompiler's SSA p-code. Every line is tagged:
 * [verified] = read directly from p-code def-use chains; [inferred] = crosses memory, calls or
 * aliasing, where the real relationship cannot be proven statically.
 */
public final class TraceTools {
	private TraceTools() {}

	private static final class Walk {
		final ToolContext ctx;
		final Program p;
		final StringBuilder sb = new StringBuilder();
		int budget = 70;
		final Set<String> seen = new HashSet<>();

		Walk(ToolContext ctx) throws ToolException {
			this.ctx = ctx;
			this.p = ctx.program();
		}

		void line(int indent, String text) {
			if (budget-- > 0) {
				sb.append("  ".repeat(indent)).append(text).append('\n');
			}
			else if (budget == -1) {
				sb.append("  ... (trace truncated; narrow with a smaller max_depth or a specific variable)\n");
			}
		}
	}

	public static void register(ToolRegistry r) {
		r.register(SimpleTool.read("trace_value",
			"Trace where a variable's value comes from (backward) or where it goes (forward) using decompiler data-flow. " +
				"Backward tracing can continue into callers when the origin is a parameter. Output labels each step [verified] or [inferred].",
			List.of(FunctionTools.FUNC,
				string("variable", "Variable name exactly as shown in the decompiler (see get_function_variables).", false),
				string("address", "Optional instruction address to trace operands of, instead of a named variable.", false),
				enumeration("direction", "backward (default) = origin; forward = uses.", false, "backward", "forward"),
				integer("max_depth", "Maximum def-use depth.", false, 1, 12, 6L),
				bool("follow_callers", "When the origin is a parameter, continue into callers (backward only).", false, true)),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				boolean fwd = "forward".equals(a.str("direction", "backward"));
				int depth = a.integer("max_depth", 6);
				Walk w = new Walk(ctx);
				var holder = ctx.decompiler.high(f, TaskMonitor.DUMMY);
				if (holder == null) {
					return ToolResult.error("The decompiler could not produce data-flow for " + Fmt.fn(f) +
						". Fall back to get_function_assembly and manual reasoning (inferred).");
				}
				HighFunction hf = holder.high();
				List<Varnode> starts = new ArrayList<>();
				String var = a.str("variable");
				if (a.str("address") != null) {
					Address ad = Resolver.address(ctx.program(), a.str("address"));
					Iterator<PcodeOpAST> it = hf.getPcodeOps(ad);
					while (it.hasNext()) {
						PcodeOp op = it.next();
						if (fwd) {
							if (op.getOutput() != null) {
								starts.add(op.getOutput());
							}
						}
						else {
							for (Varnode in : op.getInputs()) {
								if (!in.isConstant() && (var == null || var.equals(nameOf(w, in)))) {
									starts.add(in);
								}
							}
						}
					}
					if (starts.isEmpty()) {
						return ToolResult.error("No traceable operands at " + ad + " in the decompiled function (the instruction may be folded away).");
					}
				}
				else if (var != null) {
					Iterator<HighSymbol> it = hf.getLocalSymbolMap().getSymbols();
					HighSymbol sym = null;
					while (it.hasNext()) {
						HighSymbol s = it.next();
						if (var.equals(s.getName())) {
							sym = s;
						}
					}
					if (sym == null || sym.getHighVariable() == null) {
						return ToolResult.error("Variable '" + var + "' not found in " + Fmt.fn(f) + ". Use get_function_variables for valid names.");
					}
					Varnode[] inst = sym.getHighVariable().getInstances();
					if (fwd) {
						for (Varnode v : inst) {
							if (v.getDef() != null || v.isInput()) {
								starts.add(v);
							}
						}
					}
					else {
						starts.addAll(Arrays.asList(inst));
					}
				}
				else {
					return ToolResult.error("Provide 'variable' (decompiler name) or 'address'.");
				}
				w.sb.append(fwd ? "Forward" : "Backward").append(" trace in ").append(Fmt.fn(f)).append(" (depth ").append(depth)
						.append("). [verified] = p-code def-use fact; [inferred] = across memory/calls/aliasing.\n");
				int shown = 0;
				for (Varnode v : starts) {
					if (shown++ >= 3) {
						w.line(0, "(" + (starts.size() - 3) + " more instances omitted)");
						break;
					}
					w.line(0, "start: " + describe(w, v));
					if (fwd) {
						forward(w, hf, v, depth, 1);
					}
					else {
						backward(w, f, hf, v, depth, 1, a.bool("follow_callers", true) ? 2 : 0);
					}
				}
				return ToolResult.ok(w.sb.toString());
			}));
	}

	private static String nameOf(Walk w, Varnode v) {
		if (v.isConstant()) {
			return "0x" + Long.toHexString(v.getOffset());
		}
		HighVariable hv = v.getHigh();
		if (hv != null && hv.getName() != null && !hv.getName().equals("UNNAMED")) {
			return hv.getName();
		}
		if (v.isAddress() && !v.getAddress().isStackAddress()) {
			return "&" + Fmt.label(w.p, v.getAddress());
		}
		if (v.isRegister()) {
			var reg = w.p.getRegister(v.getAddress(), v.getSize());
			return reg != null ? reg.getName() : "reg_" + v.getAddress();
		}
		if (v.isUnique()) {
			return "tmp_" + Long.toHexString(v.getOffset());
		}
		return v.toString();
	}

	private static String describe(Walk w, Varnode v) {
		String n = nameOf(w, v);
		HighVariable hv = v.getHigh();
		String t = hv != null && hv.getDataType() != null ? " : " + hv.getDataType().getName() : "";
		return n + t;
	}

	private static String where(PcodeOp op) {
		return op.getSeqnum() != null && op.getSeqnum().getTarget() != null ? "@" + op.getSeqnum().getTarget() : "";
	}

	private static String callTarget(Walk w, PcodeOp op) {
		Varnode t = op.getInput(0);
		if (op.getOpcode() == PcodeOp.CALL && t.isAddress()) {
			Function c = w.p.getFunctionManager().getFunctionAt(t.getAddress());
			return c != null ? c.getName(true) : Fmt.label(w.p, t.getAddress());
		}
		return "indirect target " + nameOf(w, t);
	}

	private static String args(Walk w, PcodeOp op) {
		List<String> out = new ArrayList<>();
		for (int i = 1; i < op.getNumInputs() && i <= 6; i++) {
			out.add(nameOf(w, op.getInput(i)));
		}
		return String.join(", ", out);
	}

	private static void backward(Walk w, Function f, HighFunction hf, Varnode v, int depth, int indent,
			int callerHops) {
		w.ctx.checkCancelled();
		if (w.budget <= 0) {
			return;
		}
		if (v.isConstant()) {
			w.line(indent, "= constant 0x" + Long.toHexString(v.getOffset()) + " [verified]");
			return;
		}
		if (!w.seen.add(f.getEntryPoint() + ":" + v.hashCode())) {
			w.line(indent, "(already traced above)");
			return;
		}
		PcodeOp def = v.getDef();
		if (def == null) {
			HighVariable hv = v.getHigh();
			if (hv instanceof HighParam hp) {
				w.line(indent, "origin: parameter '" + nameOf(w, v) + "' (slot " + hp.getSlot() + ") of " + f.getName() +
					" — supplied by callers [verified]");
				if (callerHops > 0) {
					intoCallers(w, f, hp.getSlot(), depth, indent + 1, callerHops - 1);
				}
				else {
					w.line(indent + 1, "(use get_callers and trace_value in a caller to continue)");
				}
			}
			else if (v.isAddress() && !v.getAddress().isStackAddress()) {
				w.line(indent, "origin: global/memory location " + Fmt.label(w.p, v.getAddress()) +
					" — its value was set elsewhere; see get_global / get_xrefs_to [inferred]");
			}
			else {
				w.line(indent, "origin: value live on function entry or uninitialized (" + nameOf(w, v) + ") [inferred]");
			}
			return;
		}
		if (depth <= 0) {
			w.line(indent, "... depth limit reached at " + def.getMnemonic() + " " + where(def));
			return;
		}
		switch (def.getOpcode()) {
			case PcodeOp.CALL, PcodeOp.CALLIND -> {
				w.line(indent, "= return value of " + callTarget(w, def) + "(" + args(w, def) + ") " + where(def) +
					" [verified call; callee's return semantics are inferred — inspect it with get_function_decompile]");
			}
			case PcodeOp.LOAD -> {
				w.line(indent, "= value loaded from memory at pointer " + nameOf(w, def.getInput(1)) + " " + where(def) +
					" [verified load; the stored value is not tracked — inferred]");
				backward(w, f, hf, def.getInput(1), depth - 1, indent + 1, callerHops);
			}
			case PcodeOp.MULTIEQUAL -> {
				w.line(indent, "= merge of " + def.getNumInputs() + " control-flow paths " + where(def) + " [verified]");
				for (int i = 0; i < Math.min(def.getNumInputs(), 4); i++) {
					w.line(indent + 1, "path " + i + ": " + describe(w, def.getInput(i)));
					backward(w, f, hf, def.getInput(i), depth - 1, indent + 2, callerHops);
				}
			}
			case PcodeOp.INDIRECT -> {
				w.line(indent, "= possibly modified by a call/store between definitions " + where(def) + " [inferred]");
				backward(w, f, hf, def.getInput(0), depth - 1, indent + 1, callerHops);
			}
			default -> {
				StringBuilder in = new StringBuilder();
				for (Varnode x : def.getInputs()) {
					in.append(in.length() > 0 ? ", " : "").append(nameOf(w, x));
				}
				w.line(indent, "= " + def.getMnemonic() + "(" + in + ") " + where(def) + " [verified]");
				for (Varnode x : def.getInputs()) {
					if (!x.isConstant()) {
						backward(w, f, hf, x, depth - 1, indent + 1, callerHops);
					}
				}
			}
		}
	}

	private static void intoCallers(Walk w, Function f, int slot, int depth, int indent, int hops) {
		ReferenceIterator it = w.p.getReferenceManager().getReferencesTo(f.getEntryPoint());
		int sites = 0;
		while (it.hasNext() && sites < 4) {
			Reference ref = it.next();
			if (!ref.getReferenceType().isCall()) {
				continue;
			}
			Function caller = w.p.getFunctionManager().getFunctionContaining(ref.getFromAddress());
			if (caller == null) {
				continue;
			}
			sites++;
			var holder = w.ctx.decompiler.high(caller, TaskMonitor.DUMMY);
			if (holder == null) {
				w.line(indent, "caller " + Fmt.fn(caller) + ": decompiler unavailable [inferred]");
				continue;
			}
			Iterator<PcodeOpAST> ops = holder.high().getPcodeOps(ref.getFromAddress());
			boolean any = false;
			while (ops.hasNext()) {
				PcodeOp op = ops.next();
				if (op.getOpcode() == PcodeOp.CALL && slot + 1 < op.getNumInputs()) {
					any = true;
					Varnode arg = op.getInput(slot + 1);
					w.line(indent, "in caller " + Fmt.fn(caller) + " " + where(op) + ": argument = " + describe(w, arg) + " [verified]");
					backward(w, caller, holder.high(), arg, Math.max(1, depth - 2), indent + 1, hops);
				}
			}
			if (!any) {
				w.line(indent, "caller " + Fmt.fn(caller) + ": argument not recovered by decompiler at " + ref.getFromAddress() + " [inferred]");
			}
		}
		if (sites == 0) {
			w.line(indent, "no direct callers found (called indirectly or is an entry point) [inferred]");
		}
	}

	private static void forward(Walk w, HighFunction hf, Varnode v, int depth, int indent) {
		w.ctx.checkCancelled();
		if (w.budget <= 0 || depth <= 0) {
			return;
		}
		if (!w.seen.add("f:" + v.hashCode())) {
			return;
		}
		Iterator<PcodeOp> it = v.getDescendants();
		int n = 0;
		while (it.hasNext() && n++ < 8) {
			PcodeOp op = it.next();
			switch (op.getOpcode()) {
				case PcodeOp.CALL, PcodeOp.CALLIND -> {
					int argIdx = -1;
					for (int i = 1; i < op.getNumInputs(); i++) {
						if (op.getInput(i) == v) {
							argIdx = i - 1;
						}
					}
					w.line(indent, "-> passed as argument " + argIdx + " to " + callTarget(w, op) + " " + where(op) + " [verified]");
				}
				case PcodeOp.STORE -> {
					boolean asValue = op.getInput(2) == v;
					w.line(indent, asValue ? "-> stored to memory at " + nameOf(w, op.getInput(1)) + " " + where(op) + " [verified store; later readers are inferred]"
							: "-> used as the pointer of a store " + where(op) + " [verified]");
				}
				case PcodeOp.LOAD -> w.line(indent, "-> used as the pointer of a load into " + (op.getOutput() == null ? "?" : nameOf(w, op.getOutput())) + " " + where(op) + " [verified]");
				case PcodeOp.RETURN -> w.line(indent, "-> returned from the function " + where(op) + " [verified]; see get_callers to follow into callers");
				case PcodeOp.CBRANCH -> w.line(indent, "-> controls a conditional branch " + where(op) + " [verified]");
				default -> {
					Varnode out = op.getOutput();
					if (out == null) {
						w.line(indent, "-> used by " + op.getMnemonic() + " " + where(op) + " [verified]");
					}
					else {
						w.line(indent, "-> flows into " + nameOf(w, out) + " via " + op.getMnemonic() + " " + where(op) + " [verified]");
						forward(w, hf, out, depth - 1, indent + 1);
					}
				}
			}
		}
		if (it.hasNext()) {
			w.line(indent, "(more uses omitted)");
		}
	}
}

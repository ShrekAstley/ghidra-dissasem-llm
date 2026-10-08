package ghidrallm.tools.impl;

import java.util.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.data.StringDataInstance;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.TaskMonitor;

/** Formatting + small Ghidra queries shared by several tools. */
final class Fmt {
	private Fmt() {}

	static String fn(Function f) {
		return f.getName(true) + " @ " + f.getEntryPoint();
	}

	static String refType(Reference r) {
		return r.getReferenceType().getName();
	}

	/** Best human label for an address: primary symbol, or containing-function+offset, or just the address. */
	static String label(Program p, Address a) {
		Symbol s = p.getSymbolTable().getPrimarySymbol(a);
		if (s != null && s.getSource() != SourceType.DEFAULT || s != null && s.getSymbolType() == SymbolType.FUNCTION) {
			return s.getName(true) + " (" + a + ")";
		}
		Function f = p.getFunctionManager().getFunctionContaining(a);
		if (f != null) {
			long off = a.subtract(f.getEntryPoint());
			return f.getName() + (off == 0 ? "" : "+0x" + Long.toHexString(off)) + " (" + a + ")";
		}
		if (s != null) {
			return s.getName() + " (" + a + ")";
		}
		return a.toString();
	}

	/** The string literal at an address, or null. */
	static String stringAt(Program p, Address a) {
		Data d = p.getListing().getDataContaining(a);
		if (d == null || !d.hasStringValue()) {
			return null;
		}
		StringDataInstance si = StringDataInstance.getStringDataInstance(d);
		String v = si == null ? null : si.getStringValue();
		return v;
	}

	static String quote(String s, int max) {
		if (s == null) {
			return "null";
		}
		String t = s.length() > max ? s.substring(0, max) + "…" : s;
		return "\"" + t.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\"", "\\\"") + "\"";
	}

	/** One assembly line with symbolic annotations for references. */
	static String instruction(Program p, Instruction ins) {
		StringBuilder sb = new StringBuilder();
		sb.append(ins.getAddress()).append("  ").append(ins.toString());
		List<String> notes = new ArrayList<>();
		for (Reference r : ins.getReferencesFrom()) {
			if (r.getReferenceType().isFallthrough() || r.isStackReference() || r.isRegisterReference()) {
				continue;
			}
			Address to = r.getToAddress();
			if (to.isExternalAddress()) {
				Symbol ext = p.getSymbolTable().getPrimarySymbol(to);
				notes.add("-> " + (ext != null ? ext.getName(true) : to.toString()));
				continue;
			}
			String str = stringAt(p, to);
			if (str != null) {
				notes.add("-> str " + quote(str, 60));
			}
			else {
				Symbol s = p.getSymbolTable().getPrimarySymbol(to);
				if (s != null && s.getSource() != SourceType.DEFAULT || p.getFunctionManager().getFunctionAt(to) != null) {
					notes.add("-> " + label(p, to));
				}
				else {
					notes.add("-> " + to);
				}
			}
		}
		String eol = p.getListing().getComment(CommentType.EOL, ins.getAddress());
		if (eol != null) {
			notes.add("// " + eol.replace('\n', ' '));
		}
		if (!notes.isEmpty()) {
			sb.append("   ; ").append(String.join("; ", notes));
		}
		return sb.toString();
	}

	static Set<Function> callers(Function f) {
		return f.getCallingFunctions(TaskMonitor.DUMMY);
	}

	static Set<Function> callees(Function f) {
		return f.getCalledFunctions(TaskMonitor.DUMMY);
	}

	static AddressSetView body(Function f) {
		return f.getBody();
	}

	static int instructionCount(Function f) {
		int n = 0;
		for (Instruction ignored : f.getProgram().getListing().getInstructions(f.getBody(), true)) {
			n++;
		}
		return n;
	}

	static String hex(long v) {
		return "0x" + Long.toHexString(v);
	}

	static AddressSet single(Address a) {
		return new AddressSet(a, a);
	}

	static String perms(ghidra.program.model.mem.MemoryBlock b) {
		return (b.isRead() ? "r" : "-") + (b.isWrite() ? "w" : "-") + (b.isExecute() ? "x" : "-");
	}
}

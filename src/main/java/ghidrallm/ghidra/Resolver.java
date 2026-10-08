package ghidrallm.ghidra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolType;
import ghidrallm.tools.ToolContext;
import ghidrallm.tools.ToolException;

/** Turns model-supplied strings ("current", "FUN_00401000", "0x401000", "main") into Ghidra objects. */
public final class Resolver {
	private Resolver() {}

	/** Parses an address; accepts 0x-prefixed, bare hex, and space-qualified forms. */
	public static Address address(Program p, String text) throws ToolException {
		if (text == null || text.isBlank()) {
			throw new ToolException("An address is required.");
		}
		String t = text.trim();
		AddressFactory af = p.getAddressFactory();
		try {
			Address a = af.getAddress(t);
			if (a != null) {
				return a;
			}
		}
		catch (RuntimeException e) {
			// try other forms
		}
		String hex = t.toLowerCase(Locale.ROOT).startsWith("0x") ? t.substring(2) : t;
		if (hex.matches("[0-9a-fA-F]+")) {
			try {
				Address a = af.getDefaultAddressSpace().getAddress(Long.parseUnsignedLong(hex, 16));
				if (a != null) {
					return a;
				}
			}
			catch (RuntimeException e) {
				// fall through
			}
		}
		throw new ToolException("Could not parse '" + text + "' as an address.");
	}

	/** Resolves "current", an address, or a function name to a function (containing, for addresses). */
	public static Function function(ToolContext ctx, String ref) throws ToolException {
		Program p = ctx.program();
		if (ref == null || ref.isBlank() || ref.equalsIgnoreCase("current")) {
			Address a = ctx.currentAddress();
			if (a == null) {
				throw new ToolException(
					"There is no current location in Ghidra. Provide a function name or address.");
			}
			Function f = p.getFunctionManager().getFunctionContaining(a);
			if (f == null) {
				throw new ToolException("The current location (" + a + ") is not inside a function.");
			}
			return f;
		}
		return function(p, ref);
	}

	public static Function function(Program p, String ref) throws ToolException {
		String r = ref.trim();
		FunctionManager fm = p.getFunctionManager();
		// 1. exact function name(s)
		List<Function> byName = functionsNamed(p, r);
		if (byName.size() == 1) {
			return byName.get(0);
		}
		if (byName.size() > 1) {
			StringBuilder sb = new StringBuilder("Ambiguous function name '" + r + "'; candidates: ");
			for (Function f : byName) {
				sb.append(f.getName(true)).append('@').append(f.getEntryPoint()).append("; ");
			}
			throw new ToolException(sb.toString() + "Use an address.");
		}
		// 2. address
		try {
			Address a = address(p, r);
			Function f = fm.getFunctionContaining(a);
			if (f != null) {
				return f;
			}
			throw new ToolException("No function contains address " + a + ".");
		}
		catch (ToolException e) {
			if (e.getMessage().startsWith("No function")) {
				throw e;
			}
		}
		throw new ToolException("Function not found: '" + ref +
			"'. Try search_functions to locate it by partial name.");
	}

	public static List<Function> functionsNamed(Program p, String name) {
		List<Function> out = new ArrayList<>();
		SymbolIterator it = p.getSymbolTable().getSymbols(name);
		while (it.hasNext()) {
			Symbol s = it.next();
			if (s.getSymbolType() == SymbolType.FUNCTION) {
				Function f = p.getFunctionManager().getFunctionAt(s.getAddress());
				if (f != null) {
					out.add(f);
				}
			}
		}
		return out;
	}

	/** Resolves a symbol name or address text to an address (symbol names win over hex-looking names). */
	public static Address addressOrSymbol(Program p, String ref) throws ToolException {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("An address or symbol name is required.");
		}
		String r = ref.trim();
		List<Symbol> syms = new ArrayList<>();
		SymbolIterator it = p.getSymbolTable().getSymbols(r);
		while (it.hasNext() && syms.size() < 8) {
			syms.add(it.next());
		}
		if (syms.size() == 1) {
			return syms.get(0).getAddress();
		}
		if (syms.size() > 1) {
			// prefer a function/primary symbol if ambiguous
			for (Symbol s : syms) {
				if (s.getSymbolType() == SymbolType.FUNCTION) {
					return s.getAddress();
				}
			}
			return syms.get(0).getAddress();
		}
		return address(p, r);
	}
}

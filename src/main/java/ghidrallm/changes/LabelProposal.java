package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.InvalidInputException;

public class LabelProposal extends ChangeProposal {
	private final Address address;
	private String name;

	public LabelProposal(Address address, String name) {
		super(Kind.CREATE_LABEL);
		this.address = address;
		this.name = name.trim();
	}

	@Override
	public String title() {
		return "Create label " + name + " at " + address;
	}

	@Override
	public String preview(Program p) {
		Symbol s = p.getSymbolTable().getPrimarySymbol(address);
		return "Label at " + address + "\n  existing primary: " + (s == null ? "(none)" : s.getName()) +
			"\n  new label: " + name;
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Program p = ctx.program();
		if (!p.getMemory().contains(address)) {
			out.add(Problem.error("Address " + address + " is not in program memory."));
			return out;
		}
		if (name.isEmpty() || SymbolUtilities.containsInvalidChars(name)) {
			out.add(Problem.error("'" + name + "' is not a valid label name."));
		}
		for (Symbol s : p.getSymbolTable().getSymbols(address)) {
			if (s.getName().equals(name)) {
				out.add(Problem.error("That label already exists at this address."));
			}
			else if (s.getSource() == SourceType.USER_DEFINED) {
				out.add(Problem.warning("An analyst label '" + s.getName() + "' exists here; this adds another label."));
			}
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		try {
			ctx.program().getSymbolTable().createLabel(address, name, SourceType.USER_DEFINED);
		}
		catch (InvalidInputException e) {
			throw new ChangeException(e.getMessage(), e);
		}
	}

	@Override
	public String dedupeKey() {
		return "label:" + address + ":" + name;
	}

	@Override
	public String editableText() {
		return name;
	}

	@Override
	public void applyEdit(String t) throws ChangeException {
		if (t == null || t.isBlank()) {
			throw new ChangeException("Label cannot be empty.");
		}
		name = t.trim();
	}

	@Override
	public String addressText() {
		return address.toString();
	}
}

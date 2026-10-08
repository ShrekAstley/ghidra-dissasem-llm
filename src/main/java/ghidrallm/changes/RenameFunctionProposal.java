package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

public class RenameFunctionProposal extends ChangeProposal {
	private final Address entry;
	private final String oldName;
	private String newName;

	public RenameFunctionProposal(Address entry, String oldName, String newName) {
		super(Kind.RENAME_FUNCTION);
		this.entry = entry;
		this.oldName = oldName;
		this.newName = newName.trim();
	}

	@Override
	public String title() {
		return "Rename function " + oldName + " → " + newName;
	}

	@Override
	public String preview(Program p) {
		return "Rename function at " + entry + "\n  " + oldName + "  →  " + newName;
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Function f = ctx.program().getFunctionManager().getFunctionAt(entry);
		if (f == null) {
			out.add(Problem.error("No function exists at " + entry + " any more."));
			return out;
		}
		if (newName.isEmpty() || SymbolUtilities.containsInvalidChars(newName)) {
			out.add(Problem.error("'" + newName + "' is not a valid symbol name."));
		}
		if (f.getName().equals(newName)) {
			out.add(Problem.error("The function is already named '" + newName + "'."));
		}
		if (!f.getName().equals(oldName)) {
			out.add(Problem.warning("Function was renamed since this was proposed (now '" +
				f.getName() + "')."));
		}
		if (f.getSymbol().getSource() == SourceType.USER_DEFINED || f.getSymbol().getSource() == SourceType.IMPORTED) {
			out.add(Problem.overwrite("'" + f.getName() + "' is an analyst-defined/imported name."));
		}
		if (!ctx.program().getSymbolTable().getSymbols(newName, f.getParentNamespace()).isEmpty() &&
			!f.getName().equals(newName)) {
			out.add(Problem.error("Another symbol named '" + newName + "' already exists in this namespace."));
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		Function f = ctx.program().getFunctionManager().getFunctionAt(entry);
		if (f == null) {
			throw new ChangeException("Function no longer exists.");
		}
		try {
			f.setName(newName, SourceType.USER_DEFINED);
		}
		catch (DuplicateNameException | InvalidInputException e) {
			throw new ChangeException(e.getMessage(), e);
		}
	}

	@Override
	public String dedupeKey() {
		return "rename_fn:" + entry + ":" + newName;
	}

	@Override
	public String editableText() {
		return newName;
	}

	@Override
	public void applyEdit(String text) throws ChangeException {
		if (text == null || text.isBlank()) {
			throw new ChangeException("Name cannot be empty.");
		}
		newName = text.trim();
	}

	@Override
	public String knowledgeSummary() {
		return "Function at " + entry + " renamed " + oldName + " → " + newName +
			(reason().isEmpty() ? "" : ". Reason: " + reason());
	}

	@Override
	public String addressText() {
		return entry.toString();
	}

	public Address entry() {
		return entry;
	}

	public String newName() {
		return newName;
	}
}

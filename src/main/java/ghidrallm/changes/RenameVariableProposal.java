package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

/** Renames (and optionally retypes) a local variable or parameter, as shown in the decompiler. */
public class RenameVariableProposal extends ChangeProposal {
	private final Address functionEntry;
	private final String functionName;
	private final String oldName;
	private String newName;
	private String newType;

	public RenameVariableProposal(Address functionEntry, String functionName, String oldName,
			String newName, String newType) {
		super(Kind.RENAME_VARIABLE);
		this.functionEntry = functionEntry;
		this.functionName = functionName;
		this.oldName = oldName;
		this.newName = newName.trim();
		this.newType = newType == null || newType.isBlank() ? null : newType.trim();
	}

	@Override
	public String title() {
		return "Rename variable " + oldName + " → " + newName + " in " + functionName;
	}

	@Override
	public String preview(Program p) {
		return "In function " + functionName + " (" + functionEntry + ")\n  " + oldName + "  →  " +
			newName + (newType != null ? "\n  type: " + newType : "");
	}

	private record Target(Variable dbVar, HighSymbol highSym) {}

	private Target find(ApplyContext ctx, Function f) {
		for (Variable v : f.getAllVariables()) {
			if (v.getName().equals(oldName)) {
				return new Target(v, null);
			}
		}
		DecompileResults r = ctx.decompiler().decompile(f, TaskMonitor.DUMMY);
		if (r.decompileCompleted() && r.getHighFunction() != null) {
			var it = r.getHighFunction().getLocalSymbolMap().getSymbols();
			while (it.hasNext()) {
				HighSymbol s = it.next();
				if (oldName.equals(s.getName())) {
					return new Target(null, s);
				}
			}
		}
		return null;
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Function f = ctx.program().getFunctionManager().getFunctionAt(functionEntry);
		if (f == null) {
			out.add(Problem.error("Function at " + functionEntry + " no longer exists."));
			return out;
		}
		if (newName.isEmpty() || SymbolUtilities.containsInvalidChars(newName)) {
			out.add(Problem.error("'" + newName + "' is not a valid variable name."));
		}
		if (newName.equals(oldName) && newType == null) {
			out.add(Problem.error("Name is unchanged."));
		}
		for (Variable v : f.getAllVariables()) {
			if (v.getName().equals(newName) && !newName.equals(oldName)) {
				out.add(Problem.error("A variable named '" + newName + "' already exists in this function."));
			}
		}
		Target t;
		try {
			t = find(ctx, f);
		}
		catch (RuntimeException e) {
			out.add(Problem.error("Decompiler failed while locating the variable: " + e.getMessage()));
			return out;
		}
		if (t == null) {
			out.add(Problem.error("Variable '" + oldName + "' was not found in " + functionName +
				" (it may already have been renamed)."));
			return out;
		}
		boolean userNamed = t.dbVar() != null ? t.dbVar().getSource() == SourceType.USER_DEFINED
				: t.highSym().isNameLocked();
		if (userNamed) {
			out.add(Problem.overwrite("'" + oldName + "' was named by an analyst."));
		}
		if (newType != null) {
			try {
				DataType dt = TypeParsing.parse(ctx.program().getDataTypeManager(), newType);
				if (dt.getLength() <= 0) {
					out.add(Problem.error("Type '" + newType + "' must have a fixed length."));
				}
			}
			catch (ChangeException e) {
				out.add(Problem.error(e.getMessage()));
			}
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		Function f = ctx.program().getFunctionManager().getFunctionAt(functionEntry);
		if (f == null) {
			throw new ChangeException("Function no longer exists.");
		}
		Target t = find(ctx, f);
		if (t == null) {
			throw new ChangeException("Variable '" + oldName + "' not found.");
		}
		DataType dt = newType == null ? null : TypeParsing.parse(ctx.program().getDataTypeManager(), newType);
		try {
			if (t.highSym() != null) {
				HighFunctionDBUtil.updateDBVariable(t.highSym(),
					newName.equals(oldName) ? null : newName, dt, SourceType.USER_DEFINED);
			}
			else {
				if (!newName.equals(oldName)) {
					t.dbVar().setName(newName, SourceType.USER_DEFINED);
				}
				if (dt != null) {
					t.dbVar().setDataType(dt, SourceType.USER_DEFINED);
				}
			}
		}
		catch (DuplicateNameException | InvalidInputException | RuntimeException e) {
			throw new ChangeException("Could not rename variable: " + e.getMessage(), e);
		}
	}

	@Override
	public String dedupeKey() {
		return "rename_var:" + functionEntry + ":" + oldName + ":" + newName + ":" + newType;
	}

	@Override
	public String editableText() {
		return newName + (newType != null ? " : " + newType : "");
	}

	@Override
	public void applyEdit(String text) throws ChangeException {
		if (text == null || text.isBlank()) {
			throw new ChangeException("Name cannot be empty.");
		}
		String[] parts = text.split(":", 2);
		newName = parts[0].trim();
		newType = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
	}

	@Override
	public String knowledgeSummary() {
		return "In " + functionName + ": variable " + oldName + " renamed to " + newName;
	}

	@Override
	public String addressText() {
		return functionEntry.toString();
	}
}

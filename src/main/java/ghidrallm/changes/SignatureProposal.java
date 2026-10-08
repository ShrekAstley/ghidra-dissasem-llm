package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.function.FunctionRenameOption;
import ghidra.app.util.parser.FunctionSignatureParser;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.FunctionDefinitionDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.task.TaskMonitor;

/** Applies a C-style prototype ("int load_asset(char *path, int flags)") to a function. */
public class SignatureProposal extends ChangeProposal {
	private final Address entry;
	private final String functionName;
	private String signatureText;

	public SignatureProposal(Address entry, String functionName, String signatureText) {
		super(Kind.FUNCTION_SIGNATURE);
		this.entry = entry;
		this.functionName = functionName;
		this.signatureText = signatureText.trim().replaceAll(";$", "");
	}

	@Override
	public String title() {
		return "Set signature of " + functionName;
	}

	@Override
	public String preview(Program p) {
		Function f = p.getFunctionManager().getFunctionAt(entry);
		return "Function at " + entry + "\n  current: " +
			(f == null ? "(missing)" : f.getPrototypeString(false, false)) + "\n  proposed: " +
			signatureText;
	}

	private FunctionDefinitionDataType parse(Program p, Function f) throws ChangeException {
		try {
			return new FunctionSignatureParser(p.getDataTypeManager(), null).parse(f.getSignature(),
				signatureText);
		}
		catch (ghidra.util.exception.CancelledException | ghidra.app.util.cparser.C.ParseException e) {
			throw new ChangeException("Cannot parse signature: " + e.getMessage());
		}
		catch (RuntimeException e) {
			throw new ChangeException("Cannot parse signature (unknown types?): " + e.getMessage());
		}
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Function f = ctx.program().getFunctionManager().getFunctionAt(entry);
		if (f == null) {
			out.add(Problem.error("No function at " + entry));
			return out;
		}
		try {
			parse(ctx.program(), f);
		}
		catch (ChangeException e) {
			out.add(Problem.error(e.getMessage()));
		}
		if (f.getSignatureSource() == SourceType.USER_DEFINED) {
			out.add(Problem.overwrite("This function's signature was set by an analyst."));
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		Function f = ctx.program().getFunctionManager().getFunctionAt(entry);
		if (f == null) {
			throw new ChangeException("Function no longer exists.");
		}
		FunctionDefinitionDataType def = parse(ctx.program(), f);
		ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(entry, def,
			SourceType.USER_DEFINED, false, false, DataTypeConflictHandler.DEFAULT_HANDLER,
			FunctionRenameOption.RENAME_IF_DEFAULT);
		if (!cmd.applyTo(ctx.program(), TaskMonitor.DUMMY)) {
			throw new ChangeException("Ghidra rejected the signature: " + cmd.getStatusMsg());
		}
	}

	@Override
	public String dedupeKey() {
		return "sig:" + entry + ":" + signatureText;
	}

	@Override
	public String editableText() {
		return signatureText;
	}

	@Override
	public void applyEdit(String text) throws ChangeException {
		if (text == null || text.isBlank()) {
			throw new ChangeException("Signature cannot be empty.");
		}
		signatureText = text.trim().replaceAll(";$", "");
	}

	@Override
	public String knowledgeSummary() {
		return "Signature of " + functionName + " set to: " + signatureText;
	}

	@Override
	public String addressText() {
		return entry.toString();
	}
}

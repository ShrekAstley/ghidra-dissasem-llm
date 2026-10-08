package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.*;

/** Applies a data type at a memory address (never over instructions; over existing data only with consent). */
public class ApplyTypeProposal extends ChangeProposal {
	private final Address address;
	private String typeText;

	public ApplyTypeProposal(Address address, String typeText) {
		super(Kind.APPLY_DATA_TYPE);
		this.address = address;
		this.typeText = typeText.trim();
	}

	@Override
	public String title() {
		return "Apply type " + typeText + " at " + address;
	}

	@Override
	public String preview(Program p) {
		Data d = p.getListing().getDataContaining(address);
		return "Data at " + address + "\n  current: " +
			(d == null ? "(none)" : d.getDataType().getName() + (d.isDefined() ? "" : " (undefined)")) +
			"\n  proposed: " + typeText;
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Program p = ctx.program();
		if (!p.getMemory().contains(address)) {
			out.add(Problem.error("Address " + address + " is not in program memory."));
			return out;
		}
		DataType dt;
		try {
			dt = TypeParsing.parse(p.getDataTypeManager(), typeText);
		}
		catch (ChangeException e) {
			out.add(Problem.error(e.getMessage()));
			return out;
		}
		int len = dt.getLength();
		if (len <= 0) {
			out.add(Problem.error("Type must have a fixed length."));
			return out;
		}
		Address end;
		try {
			end = address.addNoWrap(len - 1);
		}
		catch (Exception e) {
			out.add(Problem.error("Type does not fit in the address space."));
			return out;
		}
		if (!p.getMemory().contains(end)) {
			out.add(Problem.error("Type (" + len + " bytes) extends past initialized memory."));
			return out;
		}
		for (CodeUnit cu : p.getListing().getCodeUnits(new AddressSet(address, end), true)) {
			if (cu instanceof Instruction) {
				out.add(Problem.error("Would overwrite an instruction at " + cu.getAddress() + "."));
				return out;
			}
			if (cu instanceof Data d && d.isDefined() && !d.getDataType().isEquivalent(dt)) {
				out.add(Problem.overwrite("Replaces existing " + d.getDataType().getName() + " at " + d.getAddress() + "."));
			}
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		Program p = ctx.program();
		DataType dt = TypeParsing.parse(p.getDataTypeManager(), typeText);
		try {
			Address end = address.addNoWrap(dt.getLength() - 1);
			p.getListing().clearCodeUnits(address, end, false);
			p.getListing().createData(address, dt);
		}
		catch (Exception e) {
			throw new ChangeException("Could not apply type: " + e.getMessage(), e);
		}
	}

	@Override
	public String dedupeKey() {
		return "applytype:" + address + ":" + typeText;
	}

	@Override
	public String editableText() {
		return typeText;
	}

	@Override
	public void applyEdit(String t) throws ChangeException {
		if (t == null || t.isBlank()) {
			throw new ChangeException("Type cannot be empty.");
		}
		typeText = t.trim();
	}

	@Override
	public String addressText() {
		return address.toString();
	}
}

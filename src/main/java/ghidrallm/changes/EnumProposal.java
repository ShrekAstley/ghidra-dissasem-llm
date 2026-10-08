package ghidrallm.changes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ghidra.program.model.data.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SymbolUtilities;

public class EnumProposal extends ChangeProposal {
	public record Entry(String name, long value) {}

	private final String name;
	private final int size;
	private final List<Entry> entries;

	public EnumProposal(String name, int size, List<Entry> entries) {
		super(Kind.CREATE_ENUM);
		this.name = name.trim();
		this.size = size;
		this.entries = List.copyOf(entries);
	}

	@Override
	public String title() {
		return "Create enum " + name + " (" + entries.size() + " values)";
	}

	@Override
	public String preview(Program p) {
		StringBuilder sb = new StringBuilder("enum ").append(name).append(" /* ").append(size)
				.append(" bytes */ {\n");
		for (Entry e : entries) {
			sb.append("    ").append(e.name()).append(" = 0x").append(Long.toHexString(e.value())).append(",\n");
		}
		return sb.append("};\nCategory: ").append(StructureProposal.CATEGORY).toString();
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		DataTypeManager dtm = ctx.program().getDataTypeManager();
		if (name.isEmpty() || SymbolUtilities.containsInvalidChars(name)) {
			out.add(Problem.error("'" + name + "' is not a valid enum name."));
		}
		if (!(size == 1 || size == 2 || size == 4 || size == 8)) {
			out.add(Problem.error("Enum size must be 1, 2, 4 or 8."));
		}
		if (entries.isEmpty()) {
			out.add(Problem.error("An enum needs at least one value."));
		}
		if (dtm.getDataType(StructureProposal.CATEGORY, name) != null) {
			out.add(Problem.error("A type named " + name + " already exists; existing types are never replaced."));
		}
		Set<String> names = new HashSet<>();
		long max = size >= 8 ? Long.MAX_VALUE : (1L << (size * 8)) - 1;
		for (Entry e : entries) {
			if (e.name().isBlank() || SymbolUtilities.containsInvalidChars(e.name()) || !names.add(e.name())) {
				out.add(Problem.error("Invalid or duplicate enum member '" + e.name() + "'."));
			}
			if (size < 8 && (e.value() < -(max >> 1) - 1 || e.value() > max)) {
				out.add(Problem.error("Value " + e.value() + " of '" + e.name() + "' does not fit in " + size + " byte(s)."));
			}
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		EnumDataType e = new EnumDataType(StructureProposal.CATEGORY, name, size);
		for (Entry en : entries) {
			e.add(en.name(), en.value());
		}
		ctx.program().getDataTypeManager().addDataType(e, DataTypeConflictHandler.KEEP_HANDLER);
	}

	@Override
	public String dedupeKey() {
		return "enum:" + name + ":" + entries.hashCode();
	}

	@Override
	public String knowledgeSummary() {
		return "Created enum " + name + " with " + entries.size() + " values.";
	}
}

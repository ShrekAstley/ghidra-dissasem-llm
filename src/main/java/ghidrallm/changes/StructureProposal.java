package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.data.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SymbolUtilities;

/** Proposes a new structure under /LocalLLM. Never replaces an existing type. */
public class StructureProposal extends ChangeProposal {
	public static final CategoryPath CATEGORY = new CategoryPath("/LocalLLM");
	private static final int MAX_SIZE = 0x10000;

	public record Field(String type, String name, Integer offset, String comment) {}

	private String name;
	private List<Field> fields;

	public StructureProposal(String name, List<Field> fields) {
		super(Kind.CREATE_STRUCTURE);
		this.name = name.trim();
		this.fields = List.copyOf(fields);
	}

	@Override
	public String title() {
		return "Create structure " + name + " (" + fields.size() + " fields)";
	}

	@Override
	public String preview(Program p) {
		StringBuilder sb = new StringBuilder("struct ").append(name).append(" {\n");
		for (Field f : fields) {
			sb.append("    ").append(f.type()).append(' ').append(f.name()).append(';');
			if (f.offset() != null) {
				sb.append("  // +0x").append(Integer.toHexString(f.offset()));
			}
			if (f.comment() != null && !f.comment().isBlank()) {
				sb.append(f.offset() != null ? " " : "  // ").append(f.comment());
			}
			sb.append('\n');
		}
		return sb.append("};\nCategory: ").append(CATEGORY).toString();
	}

	private Structure build(DataTypeManager dtm) throws ChangeException {
		StructureDataType s = new StructureDataType(CATEGORY, name, 0, dtm);
		for (Field f : fields) {
			DataType dt = TypeParsing.parse(dtm, f.type());
			if (dt.getLength() <= 0) {
				throw new ChangeException("Field '" + f.name() + "' type '" + f.type() + "' is not fixed-length.");
			}
			if (f.offset() != null) {
				if (f.offset() < s.getLength()) {
					throw new ChangeException("Field '" + f.name() + "' at +0x" + Integer.toHexString(f.offset()) +
						" overlaps the previous field (structure is already 0x" + Integer.toHexString(s.getLength()) + " bytes).");
				}
				if (f.offset() > s.getLength()) {
					s.growStructure(f.offset() - s.getLength());
				}
			}
			if (s.getLength() + dt.getLength() > MAX_SIZE) {
				throw new ChangeException("Structure would exceed 0x" + Integer.toHexString(MAX_SIZE) + " bytes.");
			}
			s.add(dt, dt.getLength(), f.name(), f.comment());
		}
		return s;
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		DataTypeManager dtm = ctx.program().getDataTypeManager();
		if (name.isEmpty() || SymbolUtilities.containsInvalidChars(name)) {
			out.add(Problem.error("'" + name + "' is not a valid structure name."));
		}
		if (fields.isEmpty()) {
			out.add(Problem.error("A structure needs at least one field."));
		}
		if (dtm.getDataType(CATEGORY, name) != null) {
			out.add(Problem.error("A type named " + name + " already exists in " + CATEGORY +
				"; choose a different name (existing types are never replaced)."));
		}
		List<DataType> same = new ArrayList<>();
		dtm.findDataTypes(name, same);
		if (!same.isEmpty()) {
			out.add(Problem.warning("A type named '" + name + "' already exists elsewhere in the program."));
		}
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Field f : fields) {
			if (f.name() == null || f.name().isBlank() || !seen.add(f.name())) {
				out.add(Problem.error("Missing or duplicate field name: '" + f.name() + "'."));
			}
		}
		try {
			build(dtm);
		}
		catch (ChangeException e) {
			out.add(Problem.error(e.getMessage()));
		}
		catch (RuntimeException e) {
			out.add(Problem.error("Invalid structure: " + e.getMessage()));
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		DataTypeManager dtm = ctx.program().getDataTypeManager();
		Structure s;
		try {
			s = build(dtm);
		}
		catch (RuntimeException e) {
			throw new ChangeException("Invalid structure: " + e.getMessage(), e);
		}
		dtm.addDataType(s, DataTypeConflictHandler.KEEP_HANDLER);
	}

	@Override
	public String dedupeKey() {
		return "struct:" + name + ":" + fields.hashCode();
	}

	/** One field per line: {@code [+0xOFF] type name  // comment}; first line {@code struct NAME}. */
	@Override
	public String editableText() {
		StringBuilder sb = new StringBuilder("struct ").append(name).append('\n');
		for (Field f : fields) {
			if (f.offset() != null) {
				sb.append("+0x").append(Integer.toHexString(f.offset())).append(' ');
			}
			sb.append(f.type()).append(' ').append(f.name());
			if (f.comment() != null && !f.comment().isBlank()) {
				sb.append("  // ").append(f.comment());
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	@Override
	public void applyEdit(String text) throws ChangeException {
		String[] lines = text.split("\\R");
		String newName = null;
		List<Field> out = new ArrayList<>();
		for (String raw : lines) {
			String line = raw.trim().replaceAll(";", "");
			if (line.isEmpty() || line.equals("{") || line.equals("}")) {
				continue;
			}
			if (line.startsWith("struct ") && newName == null) {
				newName = line.substring(7).replace("{", "").trim();
				continue;
			}
			String comment = null;
			int c = line.indexOf("//");
			if (c >= 0) {
				comment = line.substring(c + 2).trim();
				line = line.substring(0, c).trim();
			}
			Integer off = null;
			if (line.startsWith("+")) {
				int sp = line.indexOf(' ');
				if (sp < 0) {
					throw new ChangeException("Bad line: " + raw);
				}
				try {
					String o = line.substring(1, sp);
					off = o.startsWith("0x") ? Integer.parseInt(o.substring(2), 16) : Integer.parseInt(o);
				}
				catch (NumberFormatException e) {
					throw new ChangeException("Bad offset in: " + raw);
				}
				line = line.substring(sp + 1).trim();
			}
			int last = line.lastIndexOf(' ');
			int star = line.lastIndexOf('*');
			int split = Math.max(last, star);
			if (split < 0 || split >= line.length() - 1) {
				throw new ChangeException("Expected '<type> <name>' in: " + raw);
			}
			String type = line.substring(0, split + (star > last ? 1 : 0)).trim();
			String fname = line.substring(split + 1).trim();
			out.add(new Field(type, fname, off, comment));
		}
		if (newName == null || newName.isEmpty()) {
			throw new ChangeException("First line must be 'struct <name>'.");
		}
		this.name = newName;
		this.fields = List.copyOf(out);
	}

	@Override
	public String knowledgeSummary() {
		return "Created structure " + name + " with " + fields.size() + " fields.";
	}
}

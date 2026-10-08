package ghidrallm.changes;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;

public class CommentProposal extends ChangeProposal {
	private final Address address;
	private final CommentType type;
	private String text;

	public CommentProposal(Address address, CommentType type, String text) {
		super(Kind.COMMENT);
		this.address = address;
		this.type = type;
		this.text = text.trim();
	}

	public static CommentType parseType(String s) {
		return switch (s == null ? "" : s.toLowerCase()) {
			case "pre" -> CommentType.PRE;
			case "eol" -> CommentType.EOL;
			case "post" -> CommentType.POST;
			case "repeatable" -> CommentType.REPEATABLE;
			default -> CommentType.PLATE;
		};
	}

	@Override
	public String title() {
		return "Add " + type.name().toLowerCase() + " comment at " + address;
	}

	@Override
	public String preview(Program p) {
		String existing = p.getListing().getComment(type, address);
		return type.name() + " comment at " + address +
			(existing != null && !existing.isBlank() ? "\n  existing:\n    " + existing.replace("\n", "\n    ") : "") +
			"\n  proposed:\n    " + text.replace("\n", "\n    ");
	}

	@Override
	public List<Problem> validate(ApplyContext ctx) {
		List<Problem> out = new ArrayList<>();
		Program p = ctx.program();
		if (!p.getMemory().contains(address)) {
			out.add(Problem.error("Address " + address + " is not in program memory."));
			return out;
		}
		if (text.isEmpty()) {
			out.add(Problem.error("Comment text is empty."));
		}
		String existing = p.getListing().getComment(type, address);
		if (existing != null && existing.equals(text)) {
			out.add(Problem.error("This comment already exists."));
		}
		else if (existing != null && !existing.isBlank()) {
			out.add(Problem.overwrite("A " + type.name().toLowerCase() + " comment already exists here."));
		}
		return out;
	}

	@Override
	protected void apply(ApplyContext ctx) throws ChangeException {
		Listing l = ctx.program().getListing();
		l.setComment(address, type, text);
	}

	@Override
	public String dedupeKey() {
		return "comment:" + address + ":" + type + ":" + text.hashCode();
	}

	@Override
	public String editableText() {
		return text;
	}

	@Override
	public void applyEdit(String t) throws ChangeException {
		if (t == null || t.isBlank()) {
			throw new ChangeException("Comment cannot be empty.");
		}
		text = t.trim();
	}

	@Override
	public String knowledgeSummary() {
		return type.name() + " comment added at " + address + ": " + ghidrallm.util.Text.oneLine(text, 160);
	}

	@Override
	public String addressText() {
		return address.toString();
	}
}

package ghidrallm.tools.impl;

import java.util.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidrallm.changes.*;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/**
 * Tools that QUEUE change proposals. They never modify the Ghidra database; the user must review and
 * approve each proposal in the Proposals tab.
 */
public final class ProposalTools {
	private ProposalTools() {}

	private static final ToolParam REASON = string("reason", "Evidence-based justification for the change (what you observed).", true);
	private static final ToolParam CONF = enumeration("confidence", "How sure you are.", false, "CONFIRMED", "LIKELY", "POSSIBLE");

	private static ToolResult queue(ToolContext ctx, ChangeProposal p, Args a) throws ToolException {
		p.withRationale(a.str("reason"), a.str("confidence", "LIKELY"));
		List<Problem> problems = ctx.proposals.validate(p);
		List<String> errors = problems.stream().filter(x -> x.severity() == Problem.Severity.ERROR).map(Problem::message).toList();
		if (!errors.isEmpty()) {
			return ToolResult.error("Proposal rejected by validation: " + String.join(" ", errors) + " Fix and propose again.");
		}
		ChangeProposal added = ctx.proposals.add(p);
		StringBuilder sb = new StringBuilder("Queued proposal " + added.id() + ": " + added.title() +
			". It is NOT applied; the user must review and approve it in the Proposals tab.");
		for (Problem x : problems) {
			if (x.severity() == Problem.Severity.OVERWRITE) {
				sb.append(" Note: ").append(x.message()).append(" Applying will require explicit user confirmation, so prefer a different target unless you have strong evidence.");
			}
			else {
				sb.append(" Warning: ").append(x.message());
			}
		}
		ctx.log.log(ghidrallm.log.DebugLog.Category.CHANGE, "Proposal queued: " + added.title(), added.preview(ctx.program()), true);
		return ToolResult.ok(sb.toString());
	}

	public static void register(ToolRegistry r) {
		r.register(new SimpleTool("propose_rename_function", ToolPermission.PROPOSE_CHANGE,
			"Propose renaming a function. Does not change the program; queues a proposal for user approval. Use descriptive snake_case names supported by evidence.",
			List.of(string("function", "Function name or address.", true), string("new_name", "Proposed name.", true), REASON, CONF),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				return queue(ctx, new RenameFunctionProposal(f.getEntryPoint(), f.getName(), a.str("new_name")), a);
			}));

		r.register(new SimpleTool("propose_rename_variable", ToolPermission.PROPOSE_CHANGE,
			"Propose renaming (and optionally retyping) a local variable or parameter, using the exact decompiler name from get_function_variables.",
			List.of(string("function", "Function name or address.", true), string("old_name", "Current variable name.", true),
				string("new_name", "Proposed name.", true), string("new_type", "Optional C type, e.g. 'char *'.", false), REASON, CONF),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				return queue(ctx, new RenameVariableProposal(f.getEntryPoint(), f.getName(), a.str("old_name"),
					a.str("new_name"), a.str("new_type")), a);
			}));

		r.register(new SimpleTool("propose_function_signature", ToolPermission.PROPOSE_CHANGE,
			"Propose a C-style prototype for a function, e.g. 'void * load_asset(char * path, int flags)'.",
			List.of(string("function", "Function name or address.", true), string("signature", "Full C prototype.", true), REASON, CONF),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				return queue(ctx, new SignatureProposal(f.getEntryPoint(), f.getName(), a.str("signature")), a);
			}));

		r.register(new SimpleTool("propose_comment", ToolPermission.PROPOSE_CHANGE,
			"Propose a comment at an address (plate = block above, pre, eol, post, repeatable).",
			List.of(string("address", "Address.", true), string("comment", "Comment text.", true),
				enumeration("type", "Comment kind (default plate).", false, "plate", "pre", "eol", "post", "repeatable"), REASON, CONF),
			(ctx, a) -> {
				Address ad = Resolver.addressOrSymbol(ctx.program(), a.str("address"));
				return queue(ctx, new CommentProposal(ad, CommentProposal.parseType(a.str("type")), a.str("comment")), a);
			}));

		r.register(new SimpleTool("propose_function_comment", ToolPermission.PROPOSE_CHANGE,
			"Propose a descriptive comment block above a function. Describe purpose, inputs, outputs and side effects, hedging uncertain claims.",
			List.of(string("function", "Function name or address.", true), string("comment", "Comment text.", true), REASON, CONF),
			(ctx, a) -> {
				Function f = Resolver.function(ctx, a.str("function"));
				return queue(ctx, new CommentProposal(f.getEntryPoint(), ghidra.program.model.listing.CommentType.PLATE, a.str("comment")), a);
			}));

		r.register(new SimpleTool("propose_label", ToolPermission.PROPOSE_CHANGE,
			"Propose a label (symbol name) at an address, e.g. for a global variable or code location.",
			List.of(string("address", "Address.", true), string("name", "Label name.", true), REASON, CONF),
			(ctx, a) -> {
				Address ad = Resolver.addressOrSymbol(ctx.program(), a.str("address"));
				return queue(ctx, new LabelProposal(ad, a.str("name")), a);
			}));

		r.register(new SimpleTool("propose_structure", ToolPermission.PROPOSE_CHANGE,
			"Propose a new structure data type (created under /LocalLLM; existing types are never replaced). fields: array of {type, name, offset (optional, decimal or 0x hex), comment (optional)}.",
			List.of(string("name", "Structure name.", true), array("fields", "Ordered field objects.", true), REASON, CONF),
			(ctx, a) -> {
				List<StructureProposal.Field> fields = new ArrayList<>();
				for (JsonElement el : a.array("fields")) {
					if (!el.isJsonObject()) {
						throw new ToolException("each field must be an object {type, name, offset?, comment?}");
					}
					JsonObject o = el.getAsJsonObject();
					if (!o.has("type") || !o.has("name")) {
						throw new ToolException("each field needs 'type' and 'name'");
					}
					Integer off = null;
					if (o.has("offset") && !o.get("offset").isJsonNull()) {
						String s = o.get("offset").getAsString().trim();
						try {
							off = s.toLowerCase().startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
						}
						catch (NumberFormatException e) {
							throw new ToolException("bad offset '" + s + "'");
						}
					}
					fields.add(new StructureProposal.Field(o.get("type").getAsString(), o.get("name").getAsString(), off,
						o.has("comment") && !o.get("comment").isJsonNull() ? o.get("comment").getAsString() : null));
				}
				return queue(ctx, new StructureProposal(a.str("name"), fields), a);
			}));

		r.register(new SimpleTool("propose_enum", ToolPermission.PROPOSE_CHANGE,
			"Propose a new enum data type under /LocalLLM. values: array of {name, value}.",
			List.of(string("name", "Enum name.", true), integer("size", "Size in bytes (1,2,4,8).", false, 1, 8, 4L),
				array("values", "Array of {name, value}.", true), REASON, CONF),
			(ctx, a) -> {
				List<EnumProposal.Entry> entries = new ArrayList<>();
				for (JsonElement el : a.array("values")) {
					if (!el.isJsonObject() || !el.getAsJsonObject().has("name") || !el.getAsJsonObject().has("value")) {
						throw new ToolException("each value must be an object {name, value}");
					}
					JsonObject o = el.getAsJsonObject();
					String v = o.get("value").getAsString().trim();
					try {
						entries.add(new EnumProposal.Entry(o.get("name").getAsString(),
							v.toLowerCase().startsWith("0x") ? Long.parseUnsignedLong(v.substring(2), 16) : Long.parseLong(v)));
					}
					catch (NumberFormatException e) {
						throw new ToolException("bad enum value '" + v + "'");
					}
				}
				return queue(ctx, new EnumProposal(a.str("name"), a.integer("size", 4), entries), a);
			}));

		r.register(new SimpleTool("propose_apply_type", ToolPermission.PROPOSE_CHANGE,
			"Propose applying a data type (e.g. 'MyStruct', 'int', 'char *') to the data at an address. Never overwrites instructions.",
			List.of(string("address", "Address of the data.", true), string("type", "Data type name/spec.", true), REASON, CONF),
			(ctx, a) -> {
				Address ad = Resolver.addressOrSymbol(ctx.program(), a.str("address"));
				return queue(ctx, new ApplyTypeProposal(ad, a.str("type")), a);
			}));
	}
}

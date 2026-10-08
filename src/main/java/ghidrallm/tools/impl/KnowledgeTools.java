package ghidrallm.tools.impl;

import java.sql.SQLException;
import java.util.List;

import ghidrallm.knowledge.*;
import ghidrallm.tools.*;

import static ghidrallm.tools.ToolParam.*;

/** Access to the local analysis-knowledge database (never the Ghidra program). */
public final class KnowledgeTools {
	private KnowledgeTools() {}

	public static void register(ToolRegistry r) {
		r.register(new SimpleTool("recall_notes", ToolPermission.LOCAL_KNOWLEDGE,
			"Search previously stored analysis notes for this binary (summaries, hypotheses, approved renames, subsystem labels, user notes).",
			List.of(string("query", "Substring to search for (empty = most recent).", false),
				integer("limit", "Max notes.", false, 1, 30, 10L)), (ctx, a) -> {
				if (ctx.knowledge == null) {
					return ToolResult.ok("Knowledge store is disabled.");
				}
				try {
					List<Note> notes = ctx.knowledge.search(ProgramKeys.of(ctx.program()), a.str("query", ""), a.integer("limit", 10));
					if (notes.isEmpty()) {
						return ToolResult.ok("No stored notes match.");
					}
					StringBuilder sb = new StringBuilder("Stored notes (AI notes are unverified hypotheses):\n");
					for (Note n : notes) {
						sb.append("  [").append(n.kind()).append('/').append(n.source()).append("] ").append(n.subject())
								.append(n.address() != null ? " @" + n.address() : "").append(": ").append(n.text()).append('\n');
					}
					return ToolResult.ok(sb.toString());
				}
				catch (SQLException e) {
					return ToolResult.error("Knowledge store error: " + e.getMessage());
				}
			}));

		r.register(new SimpleTool("save_note", ToolPermission.LOCAL_KNOWLEDGE,
			"Store a finding for later sessions (local database only). Use for confirmed facts or labelled hypotheses worth remembering; it does not change the Ghidra program.",
			List.of(enumeration("kind", "Note category.", true, "FUNCTION_SUMMARY", "HYPOTHESIS", "RELATIONSHIP", "SUBSYSTEM"),
				string("subject", "What the note is about (function name, subsystem...).", true),
				string("text", "The finding. State evidence and certainty.", true),
				string("address", "Optional address.", false),
				enumeration("confidence", "Certainty.", false, "CONFIRMED", "LIKELY", "POSSIBLE", "UNKNOWN")), (ctx, a) -> {
				if (ctx.knowledge == null) {
					return ToolResult.ok("Knowledge store is disabled; note not saved.");
				}
				try {
					ctx.knowledge.add(ProgramKeys.of(ctx.program()), Note.Kind.valueOf(a.str("kind")), a.str("subject"),
						a.str("address"), a.str("text"), Note.Source.AI, a.str("confidence", "POSSIBLE"));
					return ToolResult.ok("Note saved (marked as AI-generated, unverified).");
				}
				catch (SQLException e) {
					return ToolResult.error("Knowledge store error: " + e.getMessage());
				}
			}));
	}
}

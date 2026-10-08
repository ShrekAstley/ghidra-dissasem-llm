package ghidrallm.ghidra;

import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidrallm.agent.ContextItem;
import ghidrallm.agent.ContextPriority;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.knowledge.Note;
import ghidrallm.knowledge.ProgramKeys;
import ghidrallm.tools.ToolContext;
import ghidrallm.tools.ToolRegistry;
import ghidrallm.tools.ToolResult;

/**
 * Gathers the "what is selected right now" context for a function by running the same read-only
 * tools the agent has. Everything is returned as prioritized {@link ContextItem}s; nothing is sent
 * blindly — the {@code ContextManager} decides what fits.
 */
public class ContextCollector {

	private final ToolRegistry registry;

	public ContextCollector(ToolRegistry registry) {
		this.registry = registry;
	}

	public List<ContextItem> forFunction(ToolContext ctx, Function f, boolean light) {
		List<ContextItem> items = new ArrayList<>();
		String addr = f.getEntryPoint().toString();
		String arg = "{\"function\":\"" + addr + "\"}";
		String name = f.getName();
		items.add(item(ContextPriority.CURRENT_FUNCTION, "sig", addr, "Function " + name + " (" + addr + ")",
			run(ctx, "get_function", arg) + run(ctx, "get_function_signature", arg), null));
		items.add(item(ContextPriority.ASSEMBLY, "asm", addr, "Assembly of " + name,
			run(ctx, "get_function_assembly", "{\"function\":\"" + addr + "\",\"max_instructions\":" + (light ? 80 : 160) + "}"),
			"get_function_assembly"));
		items.add(item(ContextPriority.DECOMPILATION, "dec", addr, "Decompiler output of " + name + " (may be inaccurate)",
			run(ctx, "get_function_decompile", arg), "get_function_decompile"));
		items.add(item(ContextPriority.CALLERS_CALLEES, "rel", addr, "Callers and callees of " + name,
			run(ctx, "get_callers", "{\"function\":\"" + addr + "\",\"limit\":12}") +
				run(ctx, "get_callees", "{\"function\":\"" + addr + "\",\"limit\":20}"),
			"get_callers / get_callees"));
		items.add(item(ContextPriority.STRINGS, "str", addr, "Strings referenced by " + name,
			run(ctx, "get_referenced_strings", "{\"function\":\"" + addr + "\",\"limit\":15}"), "get_referenced_strings"));
		items.add(item(ContextPriority.GLOBALS, "glob", addr, "Globals referenced by " + name,
			run(ctx, "get_referenced_globals", "{\"function\":\"" + addr + "\",\"limit\":12}"), "get_referenced_globals"));
		if (!light) {
			items.add(item(ContextPriority.XREFS, "xref", addr, "Cross-references to " + name,
				run(ctx, "get_xrefs_to", "{\"target\":\"" + addr + "\",\"limit\":10}"), "get_xrefs_to"));
			items.add(item(ContextPriority.RELATED_FUNCTIONS, "near", addr, "Nearby functions", neighbors(f.getProgram(), f), "search_functions"));
			String notes = notes(ctx, addr);
			if (!notes.isEmpty()) {
				items.add(item(ContextPriority.RELATED_FUNCTIONS, "notes", addr, "Stored notes for " + name, notes, "recall_notes"));
			}
		}
		return items;
	}

	/** Context when the cursor is not inside any function. */
	public List<ContextItem> forAddress(ToolContext ctx, Address a) {
		String arg = "{\"address\":\"" + a + "\"}";
		return List.of(item(ContextPriority.CURRENT_FUNCTION, "addr", a.toString(), "Cursor at " + a,
			run(ctx, "get_function_at_address", arg) + run(ctx, "get_memory", arg), null));
	}

	private ContextItem item(ContextPriority pr, String kind, String addr, String title, String text, String hint) {
		String key = kind + ":" + addr + ":" + Integer.toHexString(text.hashCode());
		return new ContextItem(pr, key, title, text, hint);
	}

	private String run(ToolContext ctx, String tool, String json) {
		ToolResult r = registry.execute(tool, json, ctx);
		return r.text() + (r.text().endsWith("\n") ? "" : "\n");
	}

	private static String neighbors(Program p, Function f) {
		FunctionManager fm = p.getFunctionManager();
		StringBuilder sb = new StringBuilder();
		Function prev = null, next = null;
		var before = fm.getFunctions(f.getEntryPoint(), false);
		if (before.hasNext()) {
			Function x = before.next();
			prev = x.equals(f) && before.hasNext() ? before.next() : x.equals(f) ? null : x;
		}
		var after = fm.getFunctions(f.getEntryPoint(), true);
		if (after.hasNext()) {
			Function x = after.next();
			next = x.equals(f) && after.hasNext() ? after.next() : x.equals(f) ? null : x;
		}
		if (prev != null) {
			sb.append("previous: ").append(prev.getName()).append(" @ ").append(prev.getEntryPoint()).append('\n');
		}
		if (next != null) {
			sb.append("next: ").append(next.getName()).append(" @ ").append(next.getEntryPoint()).append('\n');
		}
		return sb.toString();
	}

	private static String notes(ToolContext ctx, String addr) {
		KnowledgeStore k = ctx.knowledge;
		if (k == null) {
			return "";
		}
		try {
			StringBuilder sb = new StringBuilder();
			for (Note n : k.forAddress(ProgramKeys.of(ctx.program()), addr)) {
				sb.append("[").append(n.kind()).append('/').append(n.source()).append("] ").append(n.text()).append('\n');
			}
			return sb.toString();
		}
		catch (Exception e) {
			return "";
		}
	}
}

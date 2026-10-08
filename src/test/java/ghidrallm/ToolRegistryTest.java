package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ghidrallm.config.Settings;
import ghidrallm.log.DebugLog;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;

class ToolRegistryTest {

	private static ToolContext ctx() {
		return new ToolContext(() -> null, () -> null, null, null, null, new Settings(), new DebugLog(), new CancellationToken());
	}

	private static SimpleTool echo() {
		return new SimpleTool("echo", ToolPermission.READ_PROGRAM, "Echoes. Second sentence.",
			List.of(ToolParam.string("text", "t", true), ToolParam.integer("n", "count", false, 1, 5, 2L),
				ToolParam.bool("loud", "l", false, false),
				ToolParam.enumeration("mode", "m", false, "a", "b")),
			(c, a) -> ToolResult.ok(a.str("text") + ":" + a.integer("n", -1) + ":" + a.bool("loud", true) + ":" + a.str("mode", "-")));
	}

	private static ToolRegistry reg() {
		ToolRegistry r = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		r.register(echo());
		return r;
	}

	@Test
	void executesWithDefaultsAndCoercion() {
		ToolRegistry r = reg();
		assertEquals("hi:2:false:-", r.execute("echo", "{\"text\":\"hi\"}", ctx()).text());
		assertEquals("hi:3:true:b", r.execute("echo", "{\"text\":\"hi\",\"n\":\"3\",\"loud\":\"true\",\"mode\":\"b\"}", ctx()).text());
		assertEquals("hi:5:false:-", r.execute("echo", "{\"text\":\"hi\",\"n\":\"0x5\"}", ctx()).text());
	}

	@Test
	void rejectsInvalidArguments() {
		ToolRegistry r = reg();
		assertTrue(r.execute("echo", "{}", ctx()).error());
		assertTrue(r.execute("echo", "{}", ctx()).text().contains("missing required argument 'text'"));
		assertTrue(r.execute("echo", "{\"text\":\"x\",\"bogus\":1}", ctx()).text().contains("unknown argument 'bogus'"));
		assertTrue(r.execute("echo", "{\"text\":\"x\",\"n\":99}", ctx()).text().contains("between"));
		assertTrue(r.execute("echo", "{\"text\":\"x\",\"n\":\"abc\"}", ctx()).text().contains("integer"));
		assertTrue(r.execute("echo", "{\"text\":\"x\",\"mode\":\"z\"}", ctx()).text().contains("one of"));
		assertTrue(r.execute("echo", "{\"text\":\"x\",\"loud\":\"maybe\"}", ctx()).text().contains("true or false"));
		assertTrue(r.execute("echo", "{not json", ctx()).text().contains("not valid JSON"));
		assertTrue(r.execute("echo", "[1,2]", ctx()).text().contains("JSON object"));
		assertTrue(r.execute("echo", "{\"text\":{\"a\":1}}", ctx()).text().contains("string"));
	}

	@Test
	void unknownToolListsAvailable() {
		ToolResult res = reg().execute("rm_rf", "{}", ctx());
		assertTrue(res.error());
		assertTrue(res.text().contains("Unknown tool 'rm_rf'"));
		assertTrue(res.text().contains("echo"));
	}

	@Test
	void permissionsHideTools() {
		ToolRegistry r = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		r.register(echo());
		r.register(new SimpleTool("propose_x", ToolPermission.PROPOSE_CHANGE, "p", List.of(), (c, a) -> ToolResult.ok("queued")));
		assertEquals(1, r.available().size());
		assertTrue(r.execute("propose_x", "{}", ctx()).error());
		assertEquals(1, r.specs().size());
	}

	@Test
	void duplicateAndBadNamesRejected() {
		ToolRegistry r = reg();
		assertThrows(IllegalArgumentException.class, () -> r.register(echo()));
		assertThrows(IllegalArgumentException.class, () -> r.register(new SimpleTool("Bad-Name", ToolPermission.READ_PROGRAM, "d", List.of(), (c, a) -> ToolResult.ok(""))));
	}

	@Test
	void toolFailuresBecomeErrorResults() {
		ToolRegistry r = new ToolRegistry(EnumSet.of(ToolPermission.READ_PROGRAM));
		r.register(new SimpleTool("bad", ToolPermission.READ_PROGRAM, "d", List.of(), (c, a) -> {
			throw new ToolException("function not found");
		}));
		r.register(new SimpleTool("boom", ToolPermission.READ_PROGRAM, "d", List.of(), (c, a) -> {
			throw new IllegalStateException("kaput");
		}));
		assertEquals("ERROR: function not found", r.execute("bad", "{}", ctx()).text());
		assertTrue(r.execute("boom", "{}", ctx()).text().contains("failed unexpectedly"));
	}

	@Test
	void schemaIsValidAndCompactable() {
		var def = echo().definition();
		var full = def.toSpec(false);
		var compact = def.toSpec(true);
		assertEquals("object", full.parametersSchema().get("type").getAsString());
		assertEquals(1, full.parametersSchema().getAsJsonArray("required").size());
		assertEquals("Echoes.", compact.description());
		assertFalse(compact.parametersSchema().getAsJsonObject("properties").getAsJsonObject("text").has("description"));
		assertTrue(def.signature().startsWith("echo(text: string, n?: integer"));
	}

	@Test
	void realRegistryContainsRequiredTools() {
		ToolRegistry r = Tools.create(new Settings());
		for (String n : List.of("get_current_function", "get_function", "get_function_decompile", "get_function_assembly",
			"get_function_signature", "get_callers", "get_callees", "get_xrefs_to", "get_xrefs_from", "get_string",
			"search_strings", "search_symbols", "get_global", "get_memory", "read_memory", "get_data_type",
			"search_data_types", "get_function_at_address", "get_instruction_at_address", "search_functions",
			"search_program", "get_entry_points", "get_program_metadata", "get_referenced_strings", "trace_value",
			"propose_rename_function", "propose_rename_variable", "propose_structure", "propose_function_comment")) {
			assertTrue(r.find(n).isPresent(), "missing tool " + n);
		}
		Settings off = new Settings();
		off.allowProposalTools = false;
		assertTrue(Tools.create(off).find("propose_rename_function").isEmpty());
	}
}

package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.log.DebugLog;
import ghidrallm.testutil.TestPrograms;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;

/** Runs every inspection tool against a real (headless Ghidra) x86-64 program. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GhidraToolsTest {
	Program program;
	ToolRegistry registry;
	ToolContext ctx;
	DecompilerService decomp;
	KnowledgeStore knowledge;
	Address cursor;

	@BeforeAll
	void setUp() throws Exception {
		program = TestPrograms.build(this);
		decomp = new DecompilerService(30);
		knowledge = KnowledgeStore.inMemory();
		registry = Tools.create(new Settings());
		cursor = program.getAddressFactory().getDefaultAddressSpace().getAddress(TestPrograms.TEXT + 4);
		ProposalManager pm = new ProposalManager(() -> program, decomp);
		ctx = new ToolContext(() -> program, () -> cursor, decomp, pm, knowledge, new Settings(), new DebugLog(), new CancellationToken());
	}

	@AfterAll
	void tearDown() {
		decomp.close();
		knowledge.close();
		program.release(this);
	}

	String call(String tool, String args) {
		ToolResult r = registry.execute(tool, args, ctx);
		System.out.println("=== " + tool + " " + args + "\n" + r.text());
		return r.text();
	}

	@Test
	void currentFunctionUsesCursor() {
		String t = call("get_current_function", "{}");
		assertTrue(t.contains("FUN_00401000"), t);
		assertTrue(t.contains("Callers: 1") || t.contains("Callers: 2"), t); // main calls it (2 call sites, 1 caller)
		assertTrue(t.contains("Callees: 1") || t.contains("callees: 1"), t);
	}

	@Test
	void functionByNameAddressAndErrors() {
		assertTrue(call("get_function", "{\"function\":\"FUN_00401040\"}").contains("00401040"));
		assertTrue(call("get_function", "{\"function\":\"0x401044\"}").contains("FUN_00401040"));
		assertTrue(call("get_function", "{\"function\":\"401060\"}").contains("FUN_00401060"));
		String err = call("get_function", "{\"function\":\"nope_func\"}");
		assertTrue(err.startsWith("ERROR") && err.contains("Function not found"), err);
		assertTrue(call("get_function", "{\"function\":\"0x500000\"}").startsWith("ERROR"));
	}

	@Test
	void signatureAssemblyAndDecompile() {
		String sig = call("get_function_signature", "{\"function\":\"FUN_00401000\"}");
		assertTrue(sig.contains("Calling convention") && sig.contains("Parameters"), sig);
		String asm = call("get_function_assembly", "{\"function\":\"FUN_00401000\"}");
		assertTrue(asm.contains("SUB RSP") && asm.contains("CALL"), asm);
		assertTrue(asm.contains("config.dat"), "string annotated: " + asm);
		assertTrue(asm.contains("FUN_00401040"), "call target annotated");
		String paged = call("get_function_assembly", "{\"function\":\"FUN_00401000\",\"max_instructions\":3}");
		assertTrue(paged.contains("call again with start=3"), paged);
		String dec = call("get_function_decompile", "{\"function\":\"FUN_00401000\"}");
		assertTrue(dec.contains("may be inaccurate"), dec);
		assertFalse(dec.contains("[decompiler failed"), dec);
		assertTrue(dec.contains("FUN_00401040"), dec);
	}

	@Test
	void callersCalleesAndGraph() {
		String callers = call("get_callers", "{\"function\":\"FUN_00401000\"}");
		assertTrue(callers.contains("FUN_00401060") && callers.contains("00401066"), callers);
		String callees = call("get_callees", "{\"function\":\"FUN_00401060\"}");
		assertTrue(callees.contains("FUN_00401000") && callees.contains("x2"), callees);
		String graph = call("get_call_graph", "{\"function\":\"FUN_00401060\",\"depth\":2}");
		assertTrue(graph.contains("-> FUN_00401000 @ 00401000") && graph.contains("FUN_00401040"), graph);
		String up = call("get_call_graph", "{\"function\":\"FUN_00401040\",\"direction\":\"callers\",\"depth\":2}");
		assertTrue(up.contains("<- FUN_00401000") && up.contains("<- FUN_00401060"), up);
	}

	@Test
	void xrefsStringsGlobals() {
		String to = call("get_xrefs_to", "{\"target\":\"FUN_00401000\"}");
		assertTrue(to.contains("CALL") || to.contains("UNCONDITIONAL_CALL"), to);
		String from = call("get_xrefs_from", "{\"source\":\"FUN_00401000\",\"function_wide\":true}");
		assertTrue(from.contains("00402000") && from.contains("00403000"), from);
		String strs = call("get_referenced_strings", "{\"function\":\"FUN_00401000\"}");
		assertTrue(strs.contains("\"config.dat\""), strs);
		String globs = call("get_referenced_globals", "{\"function\":\"FUN_00401000\"}");
		assertTrue(globs.contains("00403000") && globs.contains("W"), globs);
		String g = call("get_global", "{\"target\":\"0x403000\"}");
		assertTrue(g.contains("writes") && g.contains("reads"), g);
		String s = call("get_string", "{\"address\":\"0x402000\"}");
		assertTrue(s.contains("config.dat") && s.contains("FUN_00401000"), s);
		assertTrue(call("search_strings", "{\"query\":\"config\"}").contains("0x402000".substring(2)));
		assertTrue(call("search_strings", "{\"query\":\"zzzz\"}").contains("No defined strings"));
	}

	@Test
	void symbolsAndSearch() {
		assertTrue(call("search_functions", "{\"query\":\"401\"}").contains("FUN_00401060"));
		String big = call("search_functions", "{\"query\":\"FUN_\",\"min_instructions\":8}");
		assertTrue(big.contains("FUN_00401060"));
		assertFalse(big.contains("FUN_00401040") || big.contains("FUN_00401000"));
		assertTrue(call("search_symbols", "{\"query\":\"FUN_\",\"kind\":\"function\"}").contains("FUN_00401040"));
		String all = call("search_program", "{\"query\":\"config\"}");
		assertTrue(all.contains("Strings:") && all.contains("config.dat"), all);
		assertTrue(call("list_functions", "{\"order\":\"xrefs\"}").contains("FUN_00401000"));
	}

	@Test
	void memoryAndInstructions() {
		assertTrue(call("get_memory", "{}").contains(".text"));
		assertTrue(call("get_memory", "{\"address\":\"0x401000\"}").contains("r-x"));
		String mem = call("read_memory", "{\"address\":\"0x402000\",\"length\":16}");
		assertTrue(mem.contains("63 6f 6e 66 69 67") && mem.contains("config"), mem);
		assertTrue(call("read_memory", "{\"address\":\"0x900000\"}").startsWith("ERROR"));
		String ins = call("get_instruction_at_address", "{\"address\":\"0x40100d\",\"count\":2}");
		assertTrue(ins.contains("CALL") && ins.contains("bytes=e8"), ins);
		assertTrue(call("get_function_at_address", "{\"address\":\"0x401045\"}").contains("FUN_00401040") ||
			call("get_function_at_address", "{\"address\":\"0x401045\"}").contains("No function"));
		assertTrue(call("get_function_at_address", "{\"address\":\"0x401010\"}").contains("FUN_00401000"));
	}

	@Test
	void dataTypes() {
		assertTrue(call("search_data_types", "{\"query\":\"^int$\"}").contains("/int"));
		assertTrue(call("get_data_type", "{\"name\":\"int\"}").contains("size 4"));
		assertTrue(call("get_data_type", "{\"name\":\"NoSuchType\"}").startsWith("ERROR"));
	}

	@Test
	void metadataAndEntryPoints() {
		String m = call("get_program_metadata", "{}");
		assertTrue(m.contains("x86:LE:64") && m.contains("Functions: 3"), m);
		assertTrue(call("get_entry_points", "{}").contains("Entry points"));
		assertTrue(call("list_imports", "{}").contains("Imports"));
	}

	@Test
	void traceValueBackwardAndForward() {
		String vars = call("get_function_variables", "{\"function\":\"FUN_00401000\"}");
		assertTrue(vars.contains("param"), vars);
		String paramName = vars.lines().filter(l -> l.trim().startsWith("param")).findFirst().get().trim().split("\\s+")[1];
		String back = call("trace_value", "{\"function\":\"FUN_00401000\",\"variable\":\"" + paramName + "\",\"direction\":\"backward\"}");
		assertTrue(back.contains("[verified]") && back.contains("parameter"), back);
		assertTrue(back.contains("in caller FUN_00401060") && back.contains("constant 0x0") && back.contains("constant 0x5"), back);
		String deep = call("trace_value", "{\"function\":\"FUN_00401040\",\"variable\":\"param_2\",\"direction\":\"backward\"}");
		assertTrue(deep.contains("FUN_00401000") && deep.contains("FUN_00401060") && deep.contains("[verified]"), deep);
		String fwd = call("trace_value", "{\"function\":\"FUN_00401000\",\"variable\":\"" + paramName + "\",\"direction\":\"forward\"}");
		assertFalse(fwd.startsWith("ERROR"), fwd);
		assertTrue(call("trace_value", "{\"function\":\"FUN_00401000\",\"variable\":\"nope\"}").startsWith("ERROR"));
		assertTrue(call("trace_value", "{\"function\":\"FUN_00401000\"}").startsWith("ERROR"));
	}

	@Test
	void knowledgeToolsRoundTrip() {
		assertTrue(call("save_note", "{\"kind\":\"HYPOTHESIS\",\"subject\":\"FUN_00401000\",\"text\":\"probably loads config\",\"address\":\"00401000\"}").contains("saved"));
		String r = call("recall_notes", "{\"query\":\"config\"}");
		assertTrue(r.contains("probably loads config") && r.contains("AI"), r);
		assertTrue(call("recall_notes", "{\"query\":\"zzz\"}").contains("No stored notes"));
	}

	@Test
	void cancellationInterruptsLongToolLoops() {
		CancellationToken t = new CancellationToken();
		t.cancel();
		ToolContext c = ctx.withCancel(t);
		assertThrows(CancellationToken.CancelledException.class, () -> registry.execute("get_function_decompile", "{\"function\":\"FUN_00401000\"}", c));
	}

	@Test
	void closedProgramGivesFriendlyError() throws Exception {
		Program p2 = TestPrograms.build("p2");
		ToolContext c = new ToolContext(() -> p2, () -> null, decomp, null, null, new Settings(), new DebugLog(), new CancellationToken());
		p2.release("p2");
		assertTrue(registry.execute("get_program_metadata", "{}", c).text().contains("No program is open"));
		ToolContext none = new ToolContext(() -> null, () -> null, decomp, null, null, new Settings(), new DebugLog(), new CancellationToken());
		assertTrue(registry.execute("get_current_function", "{}", none).text().contains("No program is open"));
	}
}

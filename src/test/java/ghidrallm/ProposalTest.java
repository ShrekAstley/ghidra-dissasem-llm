package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import ghidrallm.changes.*;
import ghidrallm.config.Settings;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.log.DebugLog;
import ghidrallm.testutil.TestPrograms;
import ghidrallm.tools.*;
import ghidrallm.util.CancellationToken;

/** Everything that can modify program state goes through the proposal gate; verify it does. */
class ProposalTest {
	Program program;
	DecompilerService decomp;
	ProposalManager pm;
	ToolRegistry registry;
	ToolContext ctx;
	Address a1000, a1040, a2000, a3000;

	@BeforeEach
	void setUp() throws Exception {
		program = TestPrograms.build(this);
		decomp = new DecompilerService(30);
		pm = new ProposalManager(() -> program, decomp);
		registry = Tools.create(new Settings());
		ctx = new ToolContext(() -> program, () -> null, decomp, pm, null, new Settings(), new DebugLog(), new CancellationToken());
		var sp = program.getAddressFactory().getDefaultAddressSpace();
		a1000 = sp.getAddress(0x401000);
		a1040 = sp.getAddress(0x401040);
		a2000 = sp.getAddress(0x402000);
		a3000 = sp.getAddress(0x403000);
	}

	@AfterEach
	void tearDown() {
		decomp.close();
		program.release(this);
	}

	private String propose(String tool, String json) {
		return registry.execute(tool, json, ctx).text();
	}

	private Function fn(Address a) {
		return program.getFunctionManager().getFunctionAt(a);
	}

	private ChangeProposal only() {
		assertEquals(1, pm.pending().size(), "pending: " + pm.pending());
		return pm.pending().get(0);
	}

	@Test
	void proposingNeverModifiesTheProgram() {
		long mod = program.getModificationNumber();
		String r = propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"load_config\",\"reason\":\"loads config.dat\"}");
		assertTrue(r.contains("NOT applied"), r);
		propose("propose_function_comment", "{\"function\":\"FUN_00401000\",\"comment\":\"Loads config.\",\"reason\":\"r\"}");
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_config\",\"reason\":\"r\"}");
		propose("propose_structure", "{\"name\":\"Cfg\",\"fields\":[{\"type\":\"int\",\"name\":\"a\"}],\"reason\":\"r\"}");
		assertEquals(4, pm.pending().size());
		assertEquals("FUN_00401000", fn(a1000).getName());
		assertEquals(mod, program.getModificationNumber(), "program untouched until approval");
		assertNull(program.getListing().getComment(CommentType.PLATE, a1000));
	}

	@Test
	void approveRenameFunctionAppliesAndIsUndoable() {
		propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"load_config\",\"reason\":\"evidence\",\"confidence\":\"LIKELY\"}");
		ChangeProposal p = only();
		assertTrue(p.preview(program).contains("FUN_00401000  →  load_config"));
		var res = pm.approve(p.id(), false);
		assertTrue(res.success(), res.message());
		assertEquals("load_config", fn(a1000).getName());
		assertEquals(SourceType.USER_DEFINED, fn(a1000).getSymbol().getSource());
		assertEquals(ChangeProposal.Status.APPLIED, p.status());
		assertTrue(pm.pending().isEmpty());
		try {
			assertTrue(program.canUndo());
			program.undo();
		}
		catch (Exception e) {
			fail(e);
		}
		assertEquals("FUN_00401000", fn(a1000).getName(), "single undo step reverts the change");
	}

	@Test
	void rejectLeavesProgramUntouched() {
		propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"x_func\",\"reason\":\"r\"}");
		ChangeProposal p = only();
		pm.reject(p.id());
		assertEquals(ChangeProposal.Status.REJECTED, p.status());
		assertEquals("FUN_00401000", fn(a1000).getName());
		assertFalse(pm.approve(p.id(), true).success(), "cannot approve a rejected proposal");
		assertEquals("FUN_00401000", fn(a1000).getName());
	}

	@Test
	void validationRejectsBadNamesAtProposalTime() {
		assertTrue(propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"bad name!\",\"reason\":\"r\"}").startsWith("ERROR"));
		assertTrue(propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"FUN_00401000\",\"reason\":\"r\"}").contains("already named"));
		assertTrue(propose("propose_rename_function", "{\"function\":\"missing\",\"new_name\":\"ok\",\"reason\":\"r\"}").contains("Function not found"));
		assertTrue(propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"ok_name\"}").contains("missing required argument 'reason'"));
		assertTrue(pm.all().isEmpty());
	}

	@Test
	void analystNamesAreProtectedUntilExplicitlyConfirmed() throws Exception {
		int tx = program.startTransaction("analyst");
		fn(a1040).setName("careful_analyst_name", SourceType.USER_DEFINED);
		program.endTransaction(tx, true);
		String r = propose("propose_rename_function", "{\"function\":\"careful_analyst_name\",\"new_name\":\"ai_guess\",\"reason\":\"r\"}");
		assertTrue(r.contains("explicit user confirmation"), r);
		ChangeProposal p = only();
		var res = pm.approve(p.id(), false);
		assertFalse(res.success());
		assertTrue(res.needsConfirmation());
		assertEquals("careful_analyst_name", fn(a1040).getName(), "not overwritten without consent");
		assertEquals(ChangeProposal.Status.PENDING, p.status());
		var res2 = pm.approve(p.id(), true);
		assertTrue(res2.success(), res2.message());
		assertEquals("ai_guess", fn(a1040).getName());
	}

	@Test
	void staleProposalFailsValidationOnApply() throws Exception {
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_cfg\",\"reason\":\"r\"}");
		ChangeProposal p = only();
		int tx = program.startTransaction("other");
		program.getSymbolTable().createLabel(a3000, "g_cfg", SourceType.USER_DEFINED);
		program.endTransaction(tx, true);
		var res = pm.approve(p.id(), false);
		assertFalse(res.success());
		assertTrue(res.message().contains("already exists"), res.message());
	}

	@Test
	void duplicatePendingProposalsAreMerged() {
		String a = "{\"function\":\"FUN_00401000\",\"new_name\":\"dup_name\",\"reason\":\"r\"}";
		propose("propose_rename_function", a);
		propose("propose_rename_function", a);
		assertEquals(1, pm.pending().size());
	}

	@Test
	void editBeforeApprove() throws Exception {
		propose("propose_rename_function", "{\"function\":\"FUN_00401000\",\"new_name\":\"first_try\",\"reason\":\"r\"}");
		ChangeProposal p = only();
		p.applyEdit("edited_name");
		assertTrue(pm.approve(p.id(), false).success());
		assertEquals("edited_name", fn(a1000).getName());
	}

	@Test
	void commentsRespectExistingText() throws Exception {
		propose("propose_function_comment", "{\"function\":\"FUN_00401000\",\"comment\":\"Loads the config.\",\"reason\":\"r\"}");
		assertTrue(pm.approve(only().id(), false).success());
		assertEquals("Loads the config.", program.getListing().getComment(CommentType.PLATE, a1000));
		propose("propose_function_comment", "{\"function\":\"FUN_00401000\",\"comment\":\"Different text.\",\"reason\":\"r\"}");
		ChangeProposal p2 = only();
		var res = pm.approve(p2.id(), false);
		assertTrue(res.needsConfirmation());
		assertEquals("Loads the config.", program.getListing().getComment(CommentType.PLATE, a1000));
		assertTrue(pm.approve(p2.id(), true).success());
		assertEquals("Different text.", program.getListing().getComment(CommentType.PLATE, a1000));
		assertTrue(propose("propose_comment", "{\"address\":\"0x900000\",\"comment\":\"x\",\"reason\":\"r\"}").startsWith("ERROR"));
		propose("propose_comment", "{\"address\":\"0x401013\",\"comment\":\"call here\",\"type\":\"eol\",\"reason\":\"r\"}");
		assertTrue(pm.approve(only().id(), false).success());
		assertEquals("call here", program.getListing().getComment(CommentType.EOL, a1000.add(0x13)));
	}

	@Test
	void labelProposal() {
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_state\",\"reason\":\"written by init\"}");
		assertTrue(pm.approve(only().id(), false).success());
		assertNotNull(program.getSymbolTable().getGlobalSymbol("g_state", a3000));
	}

	@Test
	void structureProposalCreatesUnderLocalLlmAndNeverReplaces() throws Exception {
		String json = "{\"name\":\"AssetHeader\",\"reason\":\"r\",\"fields\":[" +
			"{\"type\":\"uint32_t\",\"name\":\"magic\"},{\"type\":\"uint32_t\",\"name\":\"version\"}," +
			"{\"type\":\"uint32_t\",\"name\":\"size\"},{\"type\":\"uint64_t\",\"name\":\"data\",\"offset\":\"0x10\",\"comment\":\"pad before\"}]}";
		String r = propose("propose_structure", json);
		assertTrue(r.contains("NOT applied"), r);
		ChangeProposal p = only();
		assertTrue(p.preview(program).contains("struct AssetHeader"));
		assertNull(program.getDataTypeManager().getDataType(StructureProposal.CATEGORY, "AssetHeader"));
		var res = pm.approve(p.id(), false);
		assertTrue(res.success(), res.message());
		DataType dt = program.getDataTypeManager().getDataType(StructureProposal.CATEGORY, "AssetHeader");
		assertInstanceOf(Structure.class, dt);
		Structure s = (Structure) dt;
		assertEquals(24, s.getLength());
		assertEquals("data", s.getComponentAt(0x10).getFieldName());
		assertEquals(8, s.getComponentAt(0x10).getLength());
		// same name again: refused at proposal time, existing type untouched
		assertTrue(propose("propose_structure", json).contains("already exists"));
		assertEquals(24, ((Structure) program.getDataTypeManager().getDataType(StructureProposal.CATEGORY, "AssetHeader")).getLength());
	}

	@Test
	void structureValidationErrors() {
		assertTrue(propose("propose_structure", "{\"name\":\"S\",\"reason\":\"r\",\"fields\":[{\"type\":\"nosuchtype\",\"name\":\"a\"}]}").startsWith("ERROR"));
		assertTrue(propose("propose_structure", "{\"name\":\"S\",\"reason\":\"r\",\"fields\":[{\"type\":\"int\",\"name\":\"a\"},{\"type\":\"int\",\"name\":\"a\"}]}").contains("duplicate"));
		assertTrue(propose("propose_structure", "{\"name\":\"S\",\"reason\":\"r\",\"fields\":[{\"type\":\"int\",\"name\":\"a\"},{\"type\":\"int\",\"name\":\"b\",\"offset\":2}]}").contains("overlaps"));
		assertTrue(propose("propose_structure", "{\"name\":\"S\",\"reason\":\"r\",\"fields\":[]}").startsWith("ERROR"));
		assertTrue(propose("propose_structure", "{\"name\":\"S\",\"reason\":\"r\",\"fields\":[\"int a\"]}").contains("must be an object"));
		assertTrue(pm.all().isEmpty());
	}

	@Test
	void structureTextEditRoundTrip() throws Exception {
		propose("propose_structure", "{\"name\":\"Hdr\",\"reason\":\"r\",\"fields\":[{\"type\":\"int\",\"name\":\"a\"},{\"type\":\"char *\",\"name\":\"name\"}]}");
		ChangeProposal p = only();
		String text = p.editableText();
		assertTrue(text.startsWith("struct Hdr"));
		p.applyEdit(text.replace("struct Hdr", "struct HdrEdited") + "short extra // added by user\n");
		assertTrue(pm.approve(p.id(), false).success());
		Structure s = (Structure) program.getDataTypeManager().getDataType(StructureProposal.CATEGORY, "HdrEdited");
		assertEquals(3, s.getNumComponents());
		assertEquals("extra", s.getComponent(2).getFieldName());
		assertEquals("added by user", s.getComponent(2).getComment());
		assertThrows(ChangeException.class, () -> p.applyEdit("no struct header\n"));
	}

	@Test
	void enumProposal() {
		assertTrue(propose("propose_enum", "{\"name\":\"AssetKind\",\"size\":1,\"reason\":\"r\",\"values\":[{\"name\":\"TEXTURE\",\"value\":0},{\"name\":\"SOUND\",\"value\":\"0x1\"}]}").contains("NOT applied"));
		assertTrue(pm.approve(only().id(), false).success());
		var e = (ghidra.program.model.data.Enum) program.getDataTypeManager().getDataType(StructureProposal.CATEGORY, "AssetKind");
		assertEquals(1, e.getValue("SOUND"));
		assertEquals(1, e.getLength());
		assertTrue(propose("propose_enum", "{\"name\":\"Big\",\"size\":1,\"reason\":\"r\",\"values\":[{\"name\":\"A\",\"value\":300}]}").contains("does not fit"));
		assertTrue(propose("propose_enum", "{\"name\":\"Dup\",\"size\":4,\"reason\":\"r\",\"values\":[{\"name\":\"A\",\"value\":1},{\"name\":\"A\",\"value\":2}]}").startsWith("ERROR"));
	}

	@Test
	void applyTypeProposalProtectsInstructionsAndExistingData() throws Exception {
		assertTrue(propose("propose_apply_type", "{\"address\":\"0x401000\",\"type\":\"int\",\"reason\":\"r\"}").contains("overwrite an instruction"));
		// existing defined longlong at the global: changing it requires consent
		propose("propose_apply_type", "{\"address\":\"0x403000\",\"type\":\"int\",\"reason\":\"r\"}");
		ChangeProposal p = only();
		var res = pm.approve(p.id(), false);
		assertTrue(res.needsConfirmation(), res.message());
		assertTrue(pm.approve(p.id(), true).success());
		assertEquals("int", program.getListing().getDataAt(a3000).getDataType().getName());
		assertTrue(propose("propose_apply_type", "{\"address\":\"0x403000\",\"type\":\"bogus_t\",\"reason\":\"r\"}").startsWith("ERROR"));
	}

	@Test
	void signatureProposal() {
		String r = propose("propose_function_signature", "{\"function\":\"FUN_00401040\",\"signature\":\"long load_file(char *path, int flags)\",\"reason\":\"r\"}");
		assertTrue(r.contains("NOT applied"), r);
		var res = pm.approve(only().id(), false);
		assertTrue(res.success(), res.message());
		Function f = fn(a1040);
		assertEquals(2, f.getParameterCount());
		assertEquals("path", f.getParameter(0).getName());
		assertEquals("flags", f.getParameter(1).getName());
		assertTrue(propose("propose_function_signature", "{\"function\":\"FUN_00401040\",\"signature\":\"this is not C\",\"reason\":\"r\"}").startsWith("ERROR"));
	}

	@Test
	void variableRenameViaDecompilerSymbol() {
		String vars = registry.execute("get_function_variables", "{\"function\":\"FUN_00401040\"}", ctx).text();
		assertTrue(vars.contains("param_2"), vars);
		String r = propose("propose_rename_variable", "{\"function\":\"FUN_00401040\",\"old_name\":\"param_2\",\"new_name\":\"flags\",\"reason\":\"added to path\"}");
		assertTrue(r.contains("NOT applied"), r);
		ChangeProposal p = only();
		var res = pm.approve(p.id(), false);
		assertTrue(res.success(), res.message());
		String after = registry.execute("get_function_variables", "{\"function\":\"FUN_00401040\"}", ctx).text();
		assertTrue(after.contains("flags") && !after.contains("param_2"), after);
		assertTrue(after.contains("analyst-named"), "renamed var is now protected: " + after);
		// second AI rename of the same variable needs consent
		propose("propose_rename_variable", "{\"function\":\"FUN_00401040\",\"old_name\":\"flags\",\"new_name\":\"offset\",\"reason\":\"r\"}");
		assertTrue(pm.approve(only().id(), false).needsConfirmation());
		assertTrue(propose("propose_rename_variable", "{\"function\":\"FUN_00401040\",\"old_name\":\"nope\",\"new_name\":\"x1\",\"reason\":\"r\"}").contains("not found"));
	}

	@Test
	void approvedChangeCallbackFires() {
		var applied = new java.util.ArrayList<String>();
		pm.setOnApplied(p -> applied.add(p.title()));
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_x\",\"reason\":\"r\"}");
		pm.approve(only().id(), false);
		assertEquals(1, applied.size());
	}

	@Test
	void noProgramMeansNoApply() {
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_y\",\"reason\":\"r\"}");
		ChangeProposal p = only();
		ProposalManager orphan = new ProposalManager(() -> null, decomp);
		orphan.add(p);
		assertFalse(orphan.approve(p.id(), true).success());
		assertTrue(orphan.approve(p.id(), true).message().contains("No program"));
	}

	@Test
	void proposalToolsAreHiddenWhenPermissionRevoked() {
		Settings s = new Settings();
		s.allowProposalTools = false;
		ToolRegistry noProp = Tools.create(s);
		assertTrue(noProp.execute("propose_rename_function", "{}", ctx).text().contains("Unknown tool"));
	}

	@Test
	void changeLogRecordedAsSensitive() {
		propose("propose_label", "{\"address\":\"0x403000\",\"name\":\"g_z\",\"reason\":\"r\"}");
		assertTrue(ctx.log.snapshot().stream().anyMatch(e -> e.category() == DebugLog.Category.CHANGE && e.sensitive()));
	}

	private static void assertInstanceOf(Class<?> c, Object o) {
		Assertions.assertInstanceOf(c, o);
	}
}

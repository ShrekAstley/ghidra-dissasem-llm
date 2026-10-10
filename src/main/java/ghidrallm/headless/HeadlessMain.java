package ghidrallm.headless;

import java.io.File;
import java.nio.file.Path;

import ghidra.GhidraApplicationLayout;
import ghidra.app.script.GhidraScriptUtil;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.model.listing.Program;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.config.SecretStore;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.log.DebugLog;
import ghidrallm.mcp.McpHttpServer;
import ghidrallm.mcp.McpProtocol;
import ghidrallm.tools.ToolContext;
import ghidrallm.tools.ToolPermission;
import ghidrallm.tools.ToolRegistry;
import ghidrallm.tools.Tools;

/**
 * Headless entry point: imports + analyzes a binary with no Ghidra GUI and serves the read-only tool set over MCP
 * (loopback, bearer token). Usage:
 * {@code java -Dghidra.install.dir=<ghidra> -cp <ghidra jars>;<ext jars> ghidrallm.headless.HeadlessMain <binary> [port] [dataDir]}
 */
public final class HeadlessMain {
	private HeadlessMain() {}

	public static void main(String[] a) throws Exception {
		if (a.length < 1) {
			System.err.println("usage: HeadlessMain <binary> [port=8765] [dataDir=./headless-data] [dumpsDir=<dataDir>/dumps]");
			System.exit(2);
		}
		File bin = new File(a[0]);
		int port = a.length > 1 ? Integer.parseInt(a[1]) : 8765;
		Path data = Path.of(a.length > 2 ? a[2] : "headless-data").toAbsolutePath();
		java.nio.file.Files.createDirectories(data);
		Path dumps = Path.of(a.length > 3 ? a[3] : data.resolve("dumps").toString()).toAbsolutePath();
		java.nio.file.Files.createDirectories(dumps);

		Application.initializeApplication(new GhidraApplicationLayout(), new HeadlessGhidraApplicationConfiguration());
		// Analyzers that run Ghidra scripts (e.g. Windows resource references) NPE without a script bundle host.
		GhidraScriptUtil.acquireBundleHostReference();
		ProgramSession session = new ProgramSession();
		Program program = session.open(bin);
		System.err.println("Loaded " + program.getName() + " (" + program.getLanguageID() + ").");
		System.err.println("Analysis done: " + program.getFunctionManager().getFunctionCount() + " functions.");

		DebugLog log = new DebugLog();
		Settings s = new Settings();
		DecompilerService dec = new DecompilerService(60);
		ProposalManager props = new ProposalManager(session::current, dec);
		KnowledgeStore ks = KnowledgeStore.inMemory();
		McpProtocol proto = new McpProtocol(() -> {
				ToolRegistry reg = Tools.create(false, false);
				reg.grant(ToolPermission.LOAD_PROGRAM);
				DumpTools.register(reg, session, dumps);
				return reg;
			},
			tok -> new ToolContext(session::current, () -> null, dec, props, ks, s, log, tok), () -> s, log);
		String token = new SecretStore(data).mcpToken();
		McpHttpServer srv = new McpHttpServer(port, () -> token, proto, log);
		System.err.println("MCP: http://127.0.0.1:" + srv.port() + "/mcp");
		System.err.println("Bearer token is stored in " + data.resolve("secrets.json") + " (key mcp.token); not printed.");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			srv.close();
			proto.close();
			session.close();
			GhidraScriptUtil.releaseBundleHostReference();
		}));
		Thread.currentThread().join();
	}
}

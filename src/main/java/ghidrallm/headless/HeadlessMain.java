package ghidrallm.headless;

import java.io.File;
import java.nio.file.AccessMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ghidra.GhidraApplicationLayout;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.app.script.GhidraScriptUtil;
import ghidra.app.util.Option;
import ghidra.app.util.bin.FileByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.*;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.model.listing.Program;
import ghidra.program.util.GhidraProgramUtilities;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidrallm.changes.ProposalManager;
import ghidrallm.config.Settings;
import ghidrallm.config.SecretStore;
import ghidrallm.ghidra.DecompilerService;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.log.DebugLog;
import ghidrallm.mcp.McpHttpServer;
import ghidrallm.mcp.McpProtocol;
import ghidrallm.tools.ToolContext;
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
			System.err.println("usage: HeadlessMain <binary> [port=8765] [dataDir=./headless-data]");
			System.exit(2);
		}
		File bin = new File(a[0]);
		int port = a.length > 1 ? Integer.parseInt(a[1]) : 8765;
		Path data = Path.of(a.length > 2 ? a[2] : "headless-data").toAbsolutePath();
		java.nio.file.Files.createDirectories(data);

		Application.initializeApplication(new GhidraApplicationLayout(), new HeadlessGhidraApplicationConfiguration());
		// Analyzers that run Ghidra scripts (e.g. Windows resource references) NPE without a script bundle host.
		GhidraScriptUtil.acquireBundleHostReference();
		Object owner = new Object();
		ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
		Program program = load(bin, owner, mon);
		System.err.println("Loaded " + program.getName() + " (" + program.getLanguageID() + "); analyzing...");
		int tx = program.startTransaction("auto-analysis");
		try {
			AutoAnalysisManager am = AutoAnalysisManager.getAnalysisManager(program);
			am.initializeOptions();
			am.reAnalyzeAll(null);
			am.startAnalysis(mon);
			GhidraProgramUtilities.markProgramAnalyzed(program);
		}
		finally {
			program.endTransaction(tx, true);
		}
		System.err.println("Analysis done: " + program.getFunctionManager().getFunctionCount() + " functions.");

		DebugLog log = new DebugLog();
		Settings s = new Settings();
		DecompilerService dec = new DecompilerService(60);
		ProposalManager props = new ProposalManager(() -> program, dec);
		KnowledgeStore ks = KnowledgeStore.inMemory();
		McpProtocol proto = new McpProtocol(() -> Tools.create(false, false),
			tok -> new ToolContext(() -> program, () -> null, dec, props, ks, s, log, tok), () -> s, log);
		String token = new SecretStore(data).mcpToken();
		McpHttpServer srv = new McpHttpServer(port, () -> token, proto, log);
		System.err.println("MCP: http://127.0.0.1:" + srv.port() + "/mcp");
		System.err.println("Bearer token is stored in " + data.resolve("secrets.json") + " (key mcp.token); not printed.");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			srv.close();
			proto.close();
			program.release(owner);
			GhidraScriptUtil.releaseBundleHostReference();
		}));
		Thread.currentThread().join();
	}

	private static Program load(File bin, Object owner, ConsoleTaskMonitor mon) throws Exception {
		FileByteProvider prov = new FileByteProvider(bin, null, AccessMode.READ);
		LoadSpec best = null;
		for (Map.Entry<Loader, java.util.Collection<LoadSpec>> e : LoaderService.getAllSupportedLoadSpecs(prov).entrySet()) {
			for (LoadSpec ls : e.getValue()) {
				if (best == null || ls.isPreferred() && !best.isPreferred()) {
					best = ls;
				}
			}
		}
		if (best == null) {
			throw new IllegalStateException("No loader recognises " + bin);
		}
		List<Option> opts = best.getLoader().getDefaultOptions(prov, best, null, false, false);
		LoadResults<? extends ghidra.framework.model.DomainObject> r = best.getLoader().load(
			new Loader.ImporterSettings(prov, bin.getName(), null, null, false, best, opts, owner, new MessageLog(), mon));
		return (Program) r.getPrimaryDomainObject(owner);
	}
}

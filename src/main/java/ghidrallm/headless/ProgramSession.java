package ghidrallm.headless;

import java.io.File;
import java.nio.file.AccessMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.app.util.Option;
import ghidra.app.util.bin.FileByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.*;
import ghidra.program.model.listing.Program;
import ghidra.program.util.GhidraProgramUtilities;
import ghidra.util.task.ConsoleTaskMonitor;

/**
 * The program the headless server currently answers about. {@code import_dump} replaces it with an unpacked dump; earlier
 * programs stay open until shutdown so in-flight tool calls on them never hit a released program.
 */
public final class ProgramSession {
	private final Object owner = new Object();
	private final ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
	private final List<Program> opened = new ArrayList<>();
	private volatile Program current;

	public Object owner() {
		return owner;
	}

	public ConsoleTaskMonitor monitor() {
		return mon;
	}

	public Program current() {
		return current;
	}

	/** Loads and analyzes {@code bin}, makes it current, and returns it. */
	public synchronized Program open(File bin) throws Exception {
		Program p = load(bin);
		analyze(p);
		opened.add(p);
		current = p;
		return p;
	}

	/** Releases every program opened so far. */
	public synchronized void close() {
		for (Program p : opened) {
			p.release(owner);
		}
		opened.clear();
		current = null;
	}

	Program load(File bin) throws Exception {
		FileByteProvider prov = new FileByteProvider(bin, null, AccessMode.READ);
		LoadSpec best = null;
		for (Map.Entry<Loader, Collection<LoadSpec>> e : LoaderService.getAllSupportedLoadSpecs(prov).entrySet()) {
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

	void analyze(Program program) {
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
	}
}

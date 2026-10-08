package ghidrallm.ghidra;

import java.util.LinkedHashMap;
import java.util.Map;

import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;

/**
 * Thread-safe wrapper around Ghidra's decompiler with a small result cache. Results are keyed on
 * the program's modification number so edits (e.g. approved renames) invalidate stale output.
 */
public class DecompilerService implements AutoCloseable {

	private final int timeoutSeconds;
	private DecompInterface ifc;
	private Program openProgram;
	private final Map<String, DecompileResults> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, DecompileResults> e) {
			return size() > 24;
		}
	};

	public DecompilerService(int timeoutSeconds) {
		this.timeoutSeconds = timeoutSeconds;
	}

	/** Decompiles (or returns cached) results. Never returns null; check {@code decompileCompleted()}. */
	public synchronized DecompileResults decompile(Function f, TaskMonitor monitor) {
		Program p = f.getProgram();
		if (ifc == null || openProgram != p) {
			disposeInterface();
			ifc = new DecompInterface();
			DecompileOptions opts = new DecompileOptions();
			ifc.setOptions(opts);
			ifc.toggleCCode(true);
			ifc.toggleSyntaxTree(true);
			ifc.setSimplificationStyle("decompile");
			if (!ifc.openProgram(p)) {
				String msg = ifc.getLastMessage();
				disposeInterface();
				throw new IllegalStateException("Decompiler could not open program: " + msg);
			}
			openProgram = p;
			cache.clear();
		}
		Address entry = f.getEntryPoint();
		String key = entry + "@" + p.getModificationNumber();
		DecompileResults r = cache.get(key);
		if (r != null) {
			return r;
		}
		r = ifc.decompileFunction(f, timeoutSeconds, monitor == null ? TaskMonitor.DUMMY : monitor);
		if (r.decompileCompleted()) {
			cache.put(key, r);
		}
		return r;
	}

	/** Decompiled C text, or a short explanation of why it is unavailable. */
	public String decompiledC(Function f, TaskMonitor monitor) {
		try {
			DecompileResults r = decompile(f, monitor);
			if (r.decompileCompleted() && r.getDecompiledFunction() != null) {
				return r.getDecompiledFunction().getC();
			}
			String err = r.getErrorMessage();
			return "[decompiler failed: " + (err == null || err.isBlank() ? "unknown error" : err.trim()) + "]";
		}
		catch (RuntimeException e) {
			return "[decompiler failed: " + e.getMessage() + "]";
		}
	}

	/** High-level function (SSA data-flow) or null. */
	public HighFunctionHolder high(Function f, TaskMonitor monitor) {
		try {
			DecompileResults r = decompile(f, monitor);
			if (r.decompileCompleted() && r.getHighFunction() != null) {
				return new HighFunctionHolder(r.getHighFunction());
			}
		}
		catch (RuntimeException e) {
			// fall through
		}
		return null;
	}

	/** Tiny holder so callers do not need to import decompiler packages for a null check. */
	public record HighFunctionHolder(ghidra.program.model.pcode.HighFunction high) {}

	public synchronized void invalidate() {
		cache.clear();
	}

	private void disposeInterface() {
		if (ifc != null) {
			ifc.dispose();
			ifc = null;
			openProgram = null;
		}
	}

	@Override
	public synchronized void close() {
		cache.clear();
		disposeInterface();
	}
}

package ghidrallm.testutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import ghidra.GhidraApplicationLayout;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.util.DefaultLanguageService;
import ghidra.util.task.TaskMonitor;

/**
 * Builds a small real x86-64 program in a headless Ghidra: three functions, a string, a global, and
 * call/data references.
 *
 * <pre>
 *  0x401000 FUN_00401000(rcx=flags): calls FUN_00401040("config.dat", flags), writes global 0x403000
 *  0x401040 FUN_00401040(rcx=path, edx=flags): returns path + flags (a leaf)
 *  0x401060 FUN_00401060: calls FUN_00401000(0) and FUN_00401000(5), reads global 0x403000
 * </pre>
 */
public final class TestPrograms {
	private TestPrograms() {}

	private static boolean initialized;

	public static synchronized void initGhidra() throws Exception {
		if (initialized) {
			return;
		}
		String dir = System.getProperty("ghidra.install.dir");
		if (dir == null) {
			dir = System.getenv("GHIDRA_INSTALL_DIR");
		}
		if (!Application.isInitialized()) {
			System.setProperty("ghidra.install.dir", dir);
			trapExit();
			try {
				Application.initializeApplication(new GhidraApplicationLayout(), new HeadlessGhidraApplicationConfiguration());
			}
			catch (Throwable t) {
				System.err.println("GHIDRA INIT FAILED. java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") +
					" ghidra.install.dir=" + dir + " cwd=" + System.getProperty("user.dir"));
				t.printStackTrace();
				throw new IllegalStateException("Could not initialize headless Ghidra from '" + dir + "': " + t, t);
			}
		}
		initialized = true;
	}

	/** Turns a surprise System.exit() inside Ghidra into a visible exception with a stack trace. */
	@SuppressWarnings({ "removal", "deprecation" })
	private static void trapExit() {
		try {
			System.setSecurityManager(new SecurityManager() {
				@Override
				public void checkPermission(java.security.Permission perm) {
					// allow everything else
				}

				@Override
				public void checkPermission(java.security.Permission perm, Object context) {
					// allow everything else
				}

				@Override
				public void checkExit(int status) {
					IllegalStateException e = new IllegalStateException("System.exit(" + status + ") was called");
					e.printStackTrace();
					throw e;
				}
			});
		}
		catch (UnsupportedOperationException | SecurityException e) {
			// not allowed on this JVM; diagnostics simply unavailable
		}
	}

	public static final long TEXT = 0x401000L, STR = 0x402000L, GLOB = 0x403000L;

	public static Program build(Object consumer) throws Exception {
		initGhidra();
		Language lang = DefaultLanguageService.getLanguageService().getLanguage(new LanguageID("x86:LE:64:default"));
		CompilerSpec cspec = lang.getDefaultCompilerSpec();
		ProgramDB p = new ProgramDB("test.exe", lang, cspec, consumer);
		int tx = p.startTransaction("build");
		try {
			var as = p.getAddressFactory().getDefaultAddressSpace();
			Address text = as.getAddress(TEXT);
			byte[] code = new byte[0x100];
			java.util.Arrays.fill(code, (byte) 0xcc);
			// init_config(rcx=flags) @ +0x00:  load_file("config.dat", flags); g = result
			ByteArrayOutputStream f1 = new ByteArrayOutputStream();
			emit(f1, 0x48, 0x83, 0xec, 0x28);                // sub rsp,0x28
			emit(f1, 0x89, 0xca);                            // mov edx,ecx
			emit(f1, 0x48, 0x8d, 0x0d);                      // lea rcx,[rip+rel] -> string
			rel32(f1, TEXT + f1.size() + 4, STR);
			emit(f1, 0xe8);                                  // call load_file
			rel32(f1, TEXT + f1.size() + 4, TEXT + 0x40);
			emit(f1, 0x48, 0x89, 0x05);                      // mov [rip+rel],rax -> global
			rel32(f1, TEXT + f1.size() + 4, GLOB);
			emit(f1, 0x48, 0x83, 0xc4, 0x28, 0xc3);          // add rsp,0x28; ret
			System.arraycopy(f1.toByteArray(), 0, code, 0, f1.size());
			// load_file(rcx=path, edx=flags) @ +0x40: return path + flags
			byte[] f2 = {0x48, (byte) 0x89, (byte) 0xc8, 0x48, 0x63, (byte) 0xd2, 0x48, 0x01, (byte) 0xd0, (byte) 0xc3};
			System.arraycopy(f2, 0, code, 0x40, f2.length);
			// main @ +0x60: init_config(0); init_config(5); return g
			ByteArrayOutputStream f3 = new ByteArrayOutputStream();
			emit(f3, 0x48, 0x83, 0xec, 0x28);                // sub rsp,0x28
			emit(f3, 0x31, 0xc9);                            // xor ecx,ecx
			emit(f3, 0xe8);
			rel32(f3, TEXT + 0x60 + f3.size() + 4, TEXT);    // call init_config
			emit(f3, 0xb9, 0x05, 0x00, 0x00, 0x00);          // mov ecx,5
			emit(f3, 0xe8);
			rel32(f3, TEXT + 0x60 + f3.size() + 4, TEXT);    // call init_config
			emit(f3, 0x48, 0x8b, 0x05);                      // mov rax,[rip+rel]
			rel32(f3, TEXT + 0x60 + f3.size() + 4, GLOB);
			emit(f3, 0x48, 0x83, 0xc4, 0x28, 0xc3);          // add rsp,0x28; ret
			System.arraycopy(f3.toByteArray(), 0, code, 0x60, f3.size());

			p.getMemory().createInitializedBlock(".text", text, new ByteArrayInputStream(code), code.length, TaskMonitor.DUMMY, false);
			byte[] strBytes = "config.dat\0".getBytes();
			p.getMemory().createInitializedBlock(".rdata", as.getAddress(STR), new ByteArrayInputStream(strBytes), strBytes.length, TaskMonitor.DUMMY, false);
			p.getMemory().createInitializedBlock(".data", as.getAddress(GLOB), new ByteArrayInputStream(new byte[16]), 16, TaskMonitor.DUMMY, false);
			p.getMemory().getBlock(".text").setExecute(true);

			// disassemble + functions
			for (long off : new long[] {0, 0x40, 0x60}) {
				new DisassembleCommand(as.getAddress(TEXT + off), null, true).applyTo(p, TaskMonitor.DUMMY);
			}
			for (long off : new long[] {0, 0x40, 0x60}) {
				new CreateFunctionCmd(as.getAddress(TEXT + off)).applyTo(p, TaskMonitor.DUMMY);
			}
				p.getListing().createData(as.getAddress(STR), StringDataType.dataType);
			p.getListing().createData(as.getAddress(GLOB), LongLongDataType.dataType);
			var rm = p.getReferenceManager();
			rm.addMemoryReference(text.add(0x06), as.getAddress(STR), RefType.DATA, SourceType.ANALYSIS, 1);
			rm.addMemoryReference(text.add(0x12), as.getAddress(GLOB), RefType.WRITE, SourceType.ANALYSIS, 0);
			rm.addMemoryReference(text.add(0x75), as.getAddress(GLOB), RefType.READ, SourceType.ANALYSIS, 1);
		}
		finally {
			p.endTransaction(tx, true);
		}
		return p;
	}

	private static void emit(ByteArrayOutputStream o, int... bytes) {
		for (int b : bytes) {
			o.write(b);
		}
	}

	private static void rel32(ByteArrayOutputStream o, long nextIp, long target) {
		long rel = target - nextIp;
		for (int i = 0; i < 4; i++) {
			o.write((int) ((rel >> (8 * i)) & 0xff));
		}
	}
}

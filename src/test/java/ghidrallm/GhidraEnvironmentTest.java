package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import ghidra.program.model.listing.Program;
import ghidrallm.testutil.TestPrograms;

/**
 * Runs first among the Ghidra-backed tests and prints the environment, so a problem with the local
 * Ghidra install/JDK shows up as one clear failure instead of crashing the whole test JVM.
 */
class GhidraEnvironmentTest {
	@Test
	void headlessGhidraStartsAndBuildsAProgram() throws Exception {
		System.out.println("ENV java=" + System.getProperty("java.version") + " vendor=" + System.getProperty("java.vendor") +
			" os=" + System.getProperty("os.name") + " ghidra.install.dir=" + System.getProperty("ghidra.install.dir") +
			" maxHeapMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
		Program p = TestPrograms.build(this);
		try {
			assertEquals(3, p.getFunctionManager().getFunctionCount());
			System.out.println("ENV program ok: " + p.getLanguageID());
		}
		finally {
			p.release(this);
		}
	}
}

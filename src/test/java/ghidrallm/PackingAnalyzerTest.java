package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ghidrallm.packing.PackingAnalyzer;
import ghidrallm.packing.PackingAnalyzer.*;

/** Pure-data tests: no Ghidra needed. */
class PackingAnalyzerTest {
	static Block b(String n, boolean x, boolean init, long size, double ent) {
		return new Block(n, x, init, size, ent);
	}

	@Test
	void normalProgramIsNone() {
		Report r = PackingAnalyzer.analyze(new Facts(
			List.of(b(".text", true, true, 80_000, 6.1), b(".data", false, true, 8192, 3.0)), ".text", 120, 400, 80_000));
		assertEquals(Verdict.NONE, r.verdict());
		assertTrue(r.evidence().isEmpty());
	}

	@Test
	void themidaSectionIsLikely() {
		Report r = PackingAnalyzer.analyze(new Facts(
			List.of(b(".text", true, true, 4096, 5.0), b(".themida", true, true, 2_000_000, 7.9)), ".themida", 3, 12, 2_004_096));
		assertEquals(Verdict.LIKELY, r.verdict());
		assertEquals("Themida/WinLicense", r.packer());
		assertTrue(r.format().contains("lab VM"));
	}

	@Test
	void renamedSectionsStillDetectedByEntropyImportsAndCoverage() {
		Report r = PackingAnalyzer.analyze(new Facts(
			List.of(b("a1", true, true, 900_000, 7.8), b("a2", false, true, 4096, 2.0)), "a1", 4, 12, 900_000));
		assertEquals(Verdict.LIKELY, r.verdict());
		assertNull(r.packer());
	}

	@Test
	void upxStyleUninitializedCodeBlock() {
		Report r = PackingAnalyzer.analyze(new Facts(
			List.of(b("UPX0", true, false, 500_000, -1), b("UPX1", true, true, 200_000, 7.7)), "UPX1", 5, 1, 700_000));
		assertEquals(Verdict.LIKELY, r.verdict());
		assertEquals("UPX", r.packer());
		assertTrue(r.format().contains("own tool"));
	}

	@Test
	void singleWeakSignalIsOnlySuspected() {
		Report r = PackingAnalyzer.analyze(new Facts(List.of(b(".text", true, true, 10_000, 5.5)), ".text", 3, 40, 10_000));
		assertEquals(Verdict.SUSPECTED, r.verdict());
	}

	@Test
	void entropyOfConstantAndRandomData() {
		assertEquals(0.0, PackingAnalyzer.entropy(new byte[1000], 1000), 1e-9);
		byte[] rnd = new byte[1 << 16];
		new Random(1).nextBytes(rnd);
		assertTrue(PackingAnalyzer.entropy(rnd, rnd.length) > 7.9);
	}
}

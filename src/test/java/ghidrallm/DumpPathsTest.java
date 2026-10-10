package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ghidrallm.headless.DumpPaths;

class DumpPathsTest {
	@TempDir
	Path root;

	@Test
	void resolvesFileInsideRoot() throws Exception {
		Path f = Files.write(root.resolve("sl_dump.dll"), new byte[] { 1 });
		assertEquals(f.toRealPath(), DumpPaths.resolve(root, "sl_dump.dll"));
	}

	@Test
	void rejectsTraversalAbsoluteOutsideAndMissing() throws Exception {
		Path outside = Files.createTempFile("outside", ".bin");
		try {
			Files.write(root.resolve("a.bin"), new byte[] { 1 });
			assertThrows(IllegalArgumentException.class, () -> DumpPaths.resolve(root, "../" + outside.getFileName()));
			assertThrows(IllegalArgumentException.class, () -> DumpPaths.resolve(root, outside.toString()));
			assertThrows(IllegalArgumentException.class, () -> DumpPaths.resolve(root, "missing.bin"));
			assertThrows(IllegalArgumentException.class, () -> DumpPaths.resolve(root, " "));
		}
		finally {
			Files.deleteIfExists(outside);
		}
	}
}

package ghidrallm.headless;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Confines model-supplied dump paths to one directory. Pure Java so it can be unit tested. */
public final class DumpPaths {
	private DumpPaths() {}

	/**
	 * Resolves {@code requested} (relative to {@code root}, or absolute) and returns it only if it is an existing regular
	 * file that stays inside {@code root} after symlinks are resolved.
	 */
	public static Path resolve(Path root, String requested) throws java.io.IOException {
		if (requested == null || requested.isBlank()) {
			throw new IllegalArgumentException("path is required");
		}
		Path realRoot = root.toRealPath();
		Path p;
		try {
			p = realRoot.resolve(requested).normalize();
		}
		catch (InvalidPathException e) {
			throw new IllegalArgumentException("invalid path: " + requested);
		}
		if (!Files.isRegularFile(p)) {
			throw new IllegalArgumentException("no such file in the dumps folder: " + requested);
		}
		Path real = p.toRealPath();
		if (!real.startsWith(realRoot)) {
			throw new IllegalArgumentException("path is outside the dumps folder");
		}
		return real;
	}
}

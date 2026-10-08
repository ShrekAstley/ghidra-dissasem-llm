package ghidrallm.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Loads/saves {@link Settings} as JSON in a local directory (never a hardcoded machine path). */
public class SettingsStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;

	public SettingsStore(Path directory) {
		this.file = directory.resolve("settings.json");
	}

	public Path getFile() {
		return file;
	}

	public Settings load() {
		Settings s = new Settings();
		if (Files.isRegularFile(file)) {
			try {
				Settings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					Settings.class);
				if (loaded != null) {
					s = loaded;
				}
			}
			catch (IOException | RuntimeException e) {
				// corrupt config: fall back to defaults rather than blocking the tool
			}
		}
		s.normalize();
		return s;
	}

	public void save(Settings s) throws IOException {
		s.normalize();
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling("settings.json.tmp");
		Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}

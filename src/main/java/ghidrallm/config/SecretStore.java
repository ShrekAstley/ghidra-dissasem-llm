package ghidrallm.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Local storage for API keys and the MCP token, kept apart from {@code settings.json} so settings can
 * be shared or pasted without leaking secrets. Keys can alternatively come from an environment variable,
 * which is preferred. The file is created owner-only where the OS supports it. Not encrypted at rest.
 */
public class SecretStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final Function<String, String> env;
	private Map<String, String> data = new TreeMap<>();
	/** Keys typed in the settings dialog but not saved yet (used by Test Connection); never persisted. */
	private final Map<String, String> overrides = new TreeMap<>();

	public SecretStore(Path directory) {
		this(directory, System::getenv);
	}

	public SecretStore(Path directory, Function<String, String> env) {
		this.file = directory.resolve("secrets.json");
		this.env = env;
		load();
	}

	private synchronized void load() {
		try {
			if (Files.isRegularFile(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					new TypeToken<Map<String, String>>() {}.getType());
				data = m == null ? new TreeMap<>() : new TreeMap<>(m);
			}
		}
		catch (IOException | RuntimeException e) {
			data = new TreeMap<>();
		}
	}

	/** API key for a provider type: the named environment variable if set and non-empty, else the stored key. */
	public synchronized String apiKey(String providerType, String envVarName) {
		String o = overrides.get(providerType);
		if (o != null && !o.isBlank()) {
			return o;
		}
		if (envVarName != null && !envVarName.isBlank()) {
			String v = env.apply(envVarName.trim());
			if (v != null && !v.isBlank()) {
				return v.trim();
			}
		}
		String k = data.get("apikey." + providerType);
		return k == null || k.isBlank() ? null : k;
	}

	public synchronized void setTransientKey(String providerType, String key) {
		if (key == null || key.isBlank()) {
			overrides.remove(providerType);
		}
		else {
			overrides.put(providerType, key.trim());
		}
	}

	public synchronized void clearTransientKeys() {
		overrides.clear();
	}

	public synchronized boolean hasStoredKey(String providerType) {
		String k = data.get("apikey." + providerType);
		return k != null && !k.isBlank();
	}

	public synchronized void setApiKey(String providerType, String key) throws IOException {
		if (key == null || key.isBlank()) {
			data.remove("apikey." + providerType);
		}
		else {
			data.put("apikey." + providerType, key.trim());
		}
		save();
	}

	/** Bearer token for the local MCP server; generated on first use. */
	public synchronized String mcpToken() {
		String t = data.get("mcp.token");
		if (t == null || t.length() < 32) {
			t = newToken();
			data.put("mcp.token", t);
			try {
				save();
			}
			catch (IOException e) {
				// token still works for this session
			}
		}
		return t;
	}

	public synchronized String regenerateMcpToken() throws IOException {
		String t = newToken();
		data.put("mcp.token", t);
		save();
		return t;
	}

	private static String newToken() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	private void save() throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling("secrets.json.tmp");
		Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
		try {
			Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
		}
		catch (UnsupportedOperationException | IOException e) {
			// non-POSIX filesystem (Windows): rely on the per-user profile directory ACLs
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	public Path file() {
		return file;
	}
}

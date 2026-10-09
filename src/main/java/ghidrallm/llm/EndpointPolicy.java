package ghidrallm.llm;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import ghidrallm.config.Settings;

/**
 * Decides whether an endpoint may receive prompts. Loopback is always allowed. Any other host needs
 * explicit user consent (recorded by the settings UI), because prompts contain decompiled code,
 * assembly, strings and memory from the binary under analysis.
 */
public final class EndpointPolicy {
	private EndpointPolicy() {}

	public static boolean isLoopbackHost(String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		if (h.equals("localhost")) {
			return true;
		}
		try {
			// Only resolve literal IPs; never trigger DNS lookups for arbitrary names.
			if (!h.matches("[0-9.]+|\\[?[0-9a-f:]+\\]?")) {
				return false;
			}
			return InetAddress.getByName(h).isLoopbackAddress();
		}
		catch (UnknownHostException e) {
			return false;
		}
	}

	public static boolean isLoopbackUrl(String url) {
		try {
			return isLoopbackHost(URI.create(url.trim()).getHost());
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	public static String hostOf(String url) {
		try {
			String h = URI.create(url.trim()).getHost();
			return h == null ? "" : h.toLowerCase(Locale.ROOT);
		}
		catch (RuntimeException e) {
			return "";
		}
	}

	/** Throws {@link LLMException.Kind#ENDPOINT_NOT_LOCAL} unless the host is loopback or approved. */
	public static void check(Settings s, URI uri) throws LLMException {
		String host = uri.getHost();
		if (isLoopbackHost(host) || s.allowNonLoopbackEndpoint) {
			return;
		}
		if (host != null && s.remoteConsentHosts.stream().anyMatch(h -> h.equalsIgnoreCase(host))) {
			return;
		}
		throw new LLMException(LLMException.Kind.ENDPOINT_NOT_LOCAL, host);
	}
}

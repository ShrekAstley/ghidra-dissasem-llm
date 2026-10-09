package ghidrallm.ui;

import java.awt.Component;

import javax.swing.JOptionPane;

import ghidrallm.config.Settings;
import ghidrallm.llm.EndpointPolicy;

/** The one place a user can authorize sending program data to a non-local host. */
final class RemoteConsent {
	private RemoteConsent() {}

	static final String WARNING = """
			You are about to send data from the binary you are analyzing to a remote service.

			What leaves this machine (as part of prompts and tool results):
			  • decompiled code and disassembly of the functions you look at
			  • function/symbol names, strings, memory bytes and cross-references
			  • your questions and the conversation so far

			Consider whether you are allowed to share this binary (NDA, client or employer policy, malware samples
			that must stay in an isolated environment). Content is subject to the provider's terms and retention policy,
			and prompt text can be influenced by strings inside the binary. Usage may be billed to your account.

			The default, LM Studio on localhost, never sends anything off this machine.""";

	/**
	 * Ensures the host of {@code s.endpoint} is approved, asking the user if needed.
	 * @return true when the host is local or approved
	 */
	static boolean ensure(Component parent, Settings s) {
		if (EndpointPolicy.isLoopbackUrl(s.endpoint)) {
			return true;
		}
		String host = EndpointPolicy.hostOf(s.endpoint);
		if (host.isEmpty()) {
			JOptionPane.showMessageDialog(parent, "The endpoint URL is not valid.", "Endpoint", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		if (s.allowNonLoopbackEndpoint || s.remoteConsentHosts.stream().anyMatch(h -> h.equalsIgnoreCase(host))) {
			return true;
		}
		int ans = JOptionPane.showConfirmDialog(parent, WARNING + "\n\nSend program data to  " + host + " ?",
			"Allow remote provider", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (ans == JOptionPane.YES_OPTION) {
			s.remoteConsentHosts.add(host);
			return true;
		}
		return false;
	}
}

package ghidrallm.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

import javax.swing.*;

import ghidrallm.agent.AssistantService;
import ghidrallm.config.Settings;
import ghidrallm.llm.ConnectionReport;
import ghidrallm.llm.EndpointPolicy;

/** Modal settings editor: provider (local or remote, with consent), agent limits, MCP server, privacy/UI. */
public class SettingsDialog extends JDialog {

	private record ProviderInfo(String type, String label, String endpoint, String model, String keyEnv) {
		@Override
		public String toString() {
			return label;
		}
	}

	private static final List<ProviderInfo> PROVIDERS = List.of(
		new ProviderInfo("LMSTUDIO", "LM Studio (local, default)", "http://localhost:1234/v1", "", ""),
		new ProviderInfo("OPENAI_COMPATIBLE", "OpenAI-compatible (OpenAI, OpenRouter, Ollama, vLLM, …)", "https://api.openai.com/v1", "", "OPENAI_API_KEY"),
		new ProviderInfo("ANTHROPIC", "Anthropic Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5", "ANTHROPIC_API_KEY"));

	private final AssistantService service;
	private final Settings working;
	private final Settings original;
	private boolean saved;
	private ProviderInfo current;

	private final JComboBox<ProviderInfo> provider = new JComboBox<>(PROVIDERS.toArray(new ProviderInfo[0]));
	private final JTextField endpoint = new JTextField(24);
	private final JComboBox<String> model = new JComboBox<>();
	private final JPasswordField apiKey = new JPasswordField(24);
	private final JTextField keyEnv = new JTextField(20);
	private final JLabel keyState = new JLabel(" ");
	private final JComboBox<String> effort = new JComboBox<>(new String[] {"", "low", "medium", "high", "xhigh", "max"});
	private final JLabel remoteWarn = new JLabel(" ");
	private final JSpinner temperature = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));
	private final JSpinner maxOut = new JSpinner(new SpinnerNumberModel(2048, 64, 65536, 128));
	private final JSpinner ctxBudget = new JSpinner(new SpinnerNumberModel(16384, 1024, 1_000_000, 1024));
	private final JSpinner toolLimit = new JSpinner(new SpinnerNumberModel(16, 0, 100, 1));
	private final JSpinner steps = new JSpinner(new SpinnerNumberModel(12, 1, 50, 1));
	private final JSpinner repeats = new JSpinner(new SpinnerNumberModel(2, 1, 10, 1));
	private final JSpinner resultChars = new JSpinner(new SpinnerNumberModel(6000, 500, 100_000, 500));
	private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(180, 5, 3600, 10));
	private final JSpinner agentTimeout = new JSpinner(new SpinnerNumberModel(600, 10, 7200, 30));
	private final JSpinner analysisFns = new JSpinner(new SpinnerNumberModel(25, 1, 200, 5));
	private final JComboBox<String> toolMode = new JComboBox<>(new String[] {"AUTO", "NATIVE", "PROMPTED"});
	private final JCheckBox proposals = new JCheckBox("Let the model queue change proposals (never applied without your approval)");
	private final JCheckBox knowledgeTools = new JCheckBox("Let the model read/write the local knowledge database");
	private final JCheckBox persist = new JCheckBox("Persist analysis knowledge locally (SQLite)");
	private final JCheckBox expert = new JCheckBox("Expert mode (tool calls, raw output, timing, token usage)");
	private final JCheckBox autoCtx = new JCheckBox("Attach selected-function context to questions");
	private final JCheckBox maskLogs = new JCheckBox("Mask program data in debug log by default");
	private final JCheckBox mcpEnabled = new JCheckBox("Enable MCP server (loopback only, bearer token required)");
	private final JSpinner mcpPort = new JSpinner(new SpinnerNumberModel(8765, 1024, 65535, 1));
	private final JCheckBox mcpProposals = new JCheckBox("MCP clients may queue change proposals (you still approve each one in Ghidra)");
	private final JCheckBox mcpKnowledge = new JCheckBox("MCP clients may read/write local knowledge notes");
	private final JLabel mcpStatus = new JLabel(" ");
	private final JTextArea testOut = new JTextArea(6, 40);

	public SettingsDialog(Window owner, AssistantService service) {
		super(owner, "Local LLM RE Assistant — Settings", ModalityType.APPLICATION_MODAL);
		this.service = service;
		this.original = service.settings();
		this.working = original.copy();
		model.setEditable(true);

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Provider", scroll(providerPanel()));
		tabs.addTab("Agent", scroll(agentPanel()));
		tabs.addTab("MCP server", scroll(mcpPanel()));
		tabs.addTab("Privacy & UI", scroll(uiPanel()));

		testOut.setEditable(false);
		testOut.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
		JButton test = new JButton("Test Connection"), refresh = new JButton("Refresh models"), reset = new JButton("Defaults");
		JButton ok = new JButton("Save"), cancel = new JButton("Cancel");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(test);
		buttons.add(refresh);
		buttons.add(reset);
		buttons.add(ok);
		buttons.add(cancel);
		JPanel south = new JPanel(new BorderLayout());
		south.add(new JScrollPane(testOut), BorderLayout.CENTER);
		south.add(buttons, BorderLayout.SOUTH);
		setLayout(new BorderLayout());
		add(tabs, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		load(working);
		provider.addActionListener(e -> switchProvider());
		endpoint.getDocument().addDocumentListener(new SimpleDocListener(this::updateRemoteWarning));
		test.addActionListener(e -> runTest());
		refresh.addActionListener(e -> refreshModels());
		reset.addActionListener(e -> load(new Settings()));
		ok.addActionListener(e -> save());
		cancel.addActionListener(e -> dispose());
		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosed(java.awt.event.WindowEvent e) {
				service.secrets().clearTransientKeys();
				if (!saved) {
					service.applyTransient(original);   // discard any test-only changes
				}
			}
		});
		setPreferredSize(new Dimension(700, 800));
		pack();
		setLocationRelativeTo(owner);
	}

	public boolean wasSaved() {
		return saved;
	}

	// ---- panels --------------------------------------------------------------------------------

	private static JScrollPane scroll(JComponent c) {
		JScrollPane sp = new JScrollPane(c);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	private JPanel form() {
		JPanel p = new JPanel(new GridBagLayout());
		p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		return p;
	}

	private int row;

	private JPanel providerPanel() {
		JPanel f = form();
		row = 0;
		remoteWarn.setForeground(Theme.bad());
		GridBagConstraints wc = new GridBagConstraints();
		wc.gridx = 0;
		wc.gridy = row++;
		wc.gridwidth = 2;
		wc.anchor = GridBagConstraints.WEST;
		wc.fill = GridBagConstraints.HORIZONTAL;
		wc.insets = new Insets(2, 6, 8, 6);
		f.add(remoteWarn, wc);
		addRow(f, "Provider", provider, "Local by default. Remote providers are opt-in and ask for your consent.");
		addRow(f, "URL", endpoint, "OpenAI-compatible base URL, or the Anthropic API base URL");
		addRow(f, "Model", model, "Remote providers need an explicit model; LM Studio may be left empty");
		addRow(f, "API key", apiKey, "Remote providers only. Stored in a separate owner-only file; blank keeps the current key");
		JPanel keyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		JButton remove = new JButton("Remove stored key");
		remove.addActionListener(e -> {
			try {
				service.secrets().setApiKey(current.type(), null);
				updateKeyState();
			}
			catch (java.io.IOException ex) {
				JOptionPane.showMessageDialog(this, ex.getMessage());
			}
		});
		keyRow.add(keyState);
		keyRow.add(remove);
		addRow(f, "", keyRow, "");
		addRow(f, "API key env var", keyEnv, "Preferred: name of an environment variable holding the key (e.g. ANTHROPIC_API_KEY)");
		addRow(f, "Reasoning effort", effort, "Optional (Claude): low … max. Blank = provider default");
		addRow(f, "Temperature", temperature, "Low values (0.1–0.3) work best. Not sent to models that reject it");
		addRow(f, "Max output tokens", maxOut, "Per reply (Claude reasoning models use at least 8000)");
		addRow(f, "Context budget (tokens)", ctxBudget, "Must be ≤ the model's context length; 16384+ recommended");
		addRow(f, "Request timeout (s)", timeout, "Per HTTP request");
		addRow(f, "Tool protocol", toolMode, "AUTO tries native tool calling, then falls back to <tool_call> text (local servers only)");
		return pad(f);
	}

	private JPanel agentPanel() {
		JPanel f = form();
		row = 0;
		addRow(f, "Agent tool-call limit", toolLimit, "Max tool calls per question");
		addRow(f, "Agent step limit", steps, "Max model round-trips per question");
		addRow(f, "Max identical repeats", repeats, "Same tool+arguments beyond this are blocked");
		addRow(f, "Max tool result chars", resultChars, "Longer results are truncated before the model sees them");
		addRow(f, "Agent time limit (s)", agentTimeout, "Whole question, including tool calls");
		addRow(f, "Program-analysis functions", analysisFns, "How many key functions “Analyze Program” summarizes");
		addCheck(f, proposals);
		addCheck(f, knowledgeTools);
		addCheck(f, persist);
		return pad(f);
	}

	private JPanel mcpPanel() {
		JPanel f = form();
		row = 0;
		JTextArea about = new JTextArea("Expose this program's Ghidra tools to external MCP clients (Claude Code, Claude Desktop via the stdio bridge, Cursor, " +
			"VS Code, …). The server listens on 127.0.0.1 only and requires the bearer token below. By default clients get read-only inspection tools. " +
			"Note: whatever the client calls returns program data to that client — if it is a cloud-hosted model, that data goes to its provider. " +
			"Cloud services cannot reach a loopback address (and exposing it with a tunnel is strongly discouraged).");
		about.setEditable(false);
		about.setOpaque(false);
		about.setLineWrap(true);
		about.setWrapStyleWord(true);
		about.setColumns(46);
		about.setRows(6);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.insets = new Insets(2, 6, 8, 6);
		c.fill = GridBagConstraints.HORIZONTAL;
		f.add(about, c);
		addCheck(f, mcpEnabled);
		addRow(f, "Port", mcpPort, "Loopback port (restart happens automatically on save)");
		addCheck(f, mcpProposals);
		addCheck(f, mcpKnowledge);
		addRow(f, "Status", mcpStatus, "");
		JPanel b1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		b1.add(copyButton("Copy URL", () -> service.mcpUrl().replaceAll(":\\d+/", ":" + mcpPort.getValue() + "/")));
		b1.add(copyButton("Copy token", () -> service.secrets().mcpToken()));
		JButton regen = new JButton("Regenerate token");
		regen.addActionListener(e -> {
			try {
				service.secrets().regenerateMcpToken();
				JOptionPane.showMessageDialog(this, "New token generated. Update your MCP clients.");
			}
			catch (java.io.IOException ex) {
				JOptionPane.showMessageDialog(this, ex.getMessage());
			}
		});
		b1.add(regen);
		addRow(f, "Connect", b1, "");
		JPanel b2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		b2.add(copyButton("Claude Code command", this::claudeCodeCommand));
		b2.add(copyButton("Claude Desktop config", this::claudeDesktopConfig));
		addRow(f, "Snippets", b2, "Copies a ready-to-paste configuration (contains your token)");
		return pad(f);
	}

	private JPanel uiPanel() {
		JPanel f = form();
		row = 0;
		addCheck(f, autoCtx);
		addCheck(f, expert);
		addCheck(f, maskLogs);
		return pad(f);
	}

	private static JPanel pad(JPanel f) {
		JPanel outer = new JPanel(new BorderLayout());
		outer.add(f, BorderLayout.NORTH);
		return outer;
	}

	private JButton copyButton(String label, java.util.function.Supplier<String> text) {
		JButton b = new JButton(label);
		b.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.get()), null);
			testOut.setText("Copied to clipboard: " + label + " (clear your clipboard history if it contains a token).");
		});
		return b;
	}

	private String mcpUrl() {
		return "http://127.0.0.1:" + mcpPort.getValue() + "/mcp";
	}

	private String claudeCodeCommand() {
		return "claude mcp add --transport http ghidra " + mcpUrl() + " --header \"Authorization: Bearer " + service.secrets().mcpToken() + "\"";
	}

	private String claudeDesktopConfig() {
		// Claude Desktop launches stdio servers; use the dependency-free bridge from the examples folder.
		return "{\n  \"mcpServers\": {\n    \"ghidra\": {\n      \"command\": \"python3\",\n      \"args\": [\"/ABSOLUTE/PATH/TO/examples/mcp_stdio_bridge.py\"],\n" +
			"      \"env\": {\n        \"GHIDRA_MCP_URL\": \"" + mcpUrl() + "\",\n        \"GHIDRA_MCP_TOKEN\": \"" + service.secrets().mcpToken() + "\"\n      }\n    }\n  }\n}\n";
	}

	private void addRow(JPanel p, String label, JComponent comp, String tip) {
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 6, 3, 6);
		c.anchor = GridBagConstraints.WEST;
		c.gridy = row++;
		c.gridx = 0;
		p.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = comp instanceof JTextField || comp instanceof JComboBox ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
		p.add(comp, c);
		if (!tip.isEmpty()) {
			comp.setToolTipText(tip);
			c.gridy = row++;
			JLabel t = new JLabel("<html><body style='width:430px'>" + ghidrallm.util.Text.escapeHtml(tip) + "</body></html>");
			t.setForeground(Theme.unknown());
			t.setFont(t.getFont().deriveFont(t.getFont().getSize2D() - 1.5f));
			c.insets = new Insets(0, 6, 6, 6);
			c.fill = GridBagConstraints.NONE;
			p.add(t, c);
		}
	}

	private void addCheck(JPanel p, JCheckBox cb) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(3, 6, 3, 6);
		p.add(cb, c);
	}

	// ---- load / store --------------------------------------------------------------------------

	private ProviderInfo info(String type) {
		return PROVIDERS.stream().filter(p -> p.type().equals(type)).findFirst().orElse(PROVIDERS.get(0));
	}

	private void load(Settings s) {
		current = info(s.providerType);
		provider.setSelectedItem(current);
		setProviderFields(s.endpoint, s.model, s.apiKeyEnv);
		effort.setSelectedItem(s.effort);
		temperature.setValue(s.temperature);
		maxOut.setValue(s.maxOutputTokens);
		ctxBudget.setValue(s.contextBudgetTokens);
		toolLimit.setValue(s.maxToolCalls);
		steps.setValue(s.maxAgentSteps);
		repeats.setValue(s.maxRepeatedCalls);
		resultChars.setValue(s.maxToolResultChars);
		timeout.setValue(s.requestTimeoutSeconds);
		agentTimeout.setValue(s.agentTimeoutSeconds);
		analysisFns.setValue(s.programAnalysisMaxFunctions);
		toolMode.setSelectedItem(s.toolMode);
		proposals.setSelected(s.allowProposalTools);
		knowledgeTools.setSelected(s.allowKnowledgeTools);
		persist.setSelected(s.persistKnowledge);
		expert.setSelected(s.expertMode);
		autoCtx.setSelected(s.includeContextOnSelection);
		maskLogs.setSelected(s.maskSensitiveInLogs);
		mcpEnabled.setSelected(s.mcpEnabled);
		mcpPort.setValue(s.mcpPort);
		mcpProposals.setSelected(s.mcpAllowProposals);
		mcpKnowledge.setSelected(s.mcpAllowKnowledge);
		updateMcpStatus();
		apiKey.setText("");
		updateKeyState();
		updateRemoteWarning();
	}

	private void setProviderFields(String ep, String mdl, String env) {
		endpoint.setText(ep);
		model.removeAllItems();
		model.addItem("");
		if (mdl != null && !mdl.isBlank()) {
			model.addItem(mdl);
		}
		model.setSelectedItem(mdl == null ? "" : mdl);
		keyEnv.setText(env == null ? "" : env);
	}

	/** Remembers the fields of the provider being left and loads the remembered (or default) ones for the new one. */
	private void switchProvider() {
		ProviderInfo next = (ProviderInfo) provider.getSelectedItem();
		if (next == null || next == current) {
			return;
		}
		Settings.Profile old = new Settings.Profile();
		old.endpoint = endpoint.getText().trim();
		Object m = model.getSelectedItem();
		old.model = m == null ? "" : m.toString().trim();
		old.apiKeyEnv = keyEnv.getText().trim();
		working.profiles.put(current.type(), old);
		current = next;
		Settings.Profile p = working.profiles.get(next.type());
		if (p != null && !p.endpoint.isBlank()) {
			setProviderFields(p.endpoint, p.model, p.apiKeyEnv);
		}
		else {
			setProviderFields(next.endpoint(), next.model(), next.keyEnv());
		}
		apiKey.setText("");
		updateKeyState();
		updateRemoteWarning();
	}

	private void updateKeyState() {
		boolean remote = !current.type().equals("LMSTUDIO");
		apiKey.setEnabled(remote);
		keyEnv.setEnabled(remote);
		effort.setEnabled(current.type().equals("ANTHROPIC"));
		String env = keyEnv.getText().trim();
		if (!remote) {
			keyState.setText("No key needed for LM Studio");
		}
		else if (!env.isEmpty() && System.getenv(env) != null && !System.getenv(env).isBlank()) {
			keyState.setText("Using environment variable " + env);
		}
		else if (service.secrets().hasStoredKey(current.type())) {
			keyState.setText("A key is stored locally");
		}
		else {
			keyState.setText("No key configured");
		}
	}

	private void updateRemoteWarning() {
		String ep = endpoint.getText().trim();
		boolean remote = !EndpointPolicy.isLoopbackUrl(ep);
		remoteWarn.setText(remote ? "<html><body style='width:520px'>⚠ <b>Remote endpoint (" + ghidrallm.util.Text.escapeHtml(EndpointPolicy.hostOf(ep)) + ")</b>: prompts containing decompiled code, assembly, strings and " +
			"memory from the open binary will be sent there once you approve.</body></html>" : " ");
	}

	private void updateMcpStatus() {
		mcpStatus.setText(service.mcpError().isEmpty() ? (service.mcpRunning() ? "Running at " + service.mcpUrl() : "Stopped") : service.mcpError());
		mcpStatus.setForeground(service.mcpError().isEmpty() ? UIManager.getColor("Label.foreground") : Theme.bad());
	}

	private void store() {
		working.providerType = current.type();
		working.endpoint = endpoint.getText().trim();
		Object m = model.getSelectedItem();
		working.model = m == null ? "" : m.toString().trim();
		working.apiKeyEnv = keyEnv.getText().trim();
		working.effort = (String) effort.getSelectedItem();
		Settings.Profile p = new Settings.Profile();
		p.endpoint = working.endpoint;
		p.model = working.model;
		p.apiKeyEnv = working.apiKeyEnv;
		working.profiles.put(current.type(), p);
		working.temperature = ((Number) temperature.getValue()).doubleValue();
		working.maxOutputTokens = ((Number) maxOut.getValue()).intValue();
		working.contextBudgetTokens = ((Number) ctxBudget.getValue()).intValue();
		working.maxToolCalls = ((Number) toolLimit.getValue()).intValue();
		working.maxAgentSteps = ((Number) steps.getValue()).intValue();
		working.maxRepeatedCalls = ((Number) repeats.getValue()).intValue();
		working.maxToolResultChars = ((Number) resultChars.getValue()).intValue();
		working.requestTimeoutSeconds = ((Number) timeout.getValue()).intValue();
		working.agentTimeoutSeconds = ((Number) agentTimeout.getValue()).intValue();
		working.programAnalysisMaxFunctions = ((Number) analysisFns.getValue()).intValue();
		working.toolMode = (String) toolMode.getSelectedItem();
		working.allowProposalTools = proposals.isSelected();
		working.allowKnowledgeTools = knowledgeTools.isSelected();
		working.persistKnowledge = persist.isSelected();
		working.expertMode = expert.isSelected();
		working.includeContextOnSelection = autoCtx.isSelected();
		working.maskSensitiveInLogs = maskLogs.isSelected();
		working.mcpEnabled = mcpEnabled.isSelected();
		working.mcpPort = ((Number) mcpPort.getValue()).intValue();
		working.mcpAllowProposals = mcpProposals.isSelected();
		working.mcpAllowKnowledge = mcpKnowledge.isSelected();
	}

	private void save() {
		store();
		if (!RemoteConsent.ensure(this, working)) {
			return;
		}
		try {
			String typed = new String(apiKey.getPassword()).trim();
			if (!typed.isEmpty()) {
				service.secrets().setApiKey(current.type(), typed);
			}
			service.updateSettings(working);
			saved = true;
			dispose();
		}
		catch (Exception ex) {
			JOptionPane.showMessageDialog(this, "Could not save settings: " + ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
		}
	}

	// ---- testing -------------------------------------------------------------------------------

	/** Applies the dialog's values in memory only (including a typed key) so they can be tested before saving. */
	private boolean applyTemporarily() {
		store();
		if (!RemoteConsent.ensure(this, working)) {
			testOut.setText("Test cancelled: the remote host was not approved.");
			return false;
		}
		service.secrets().setTransientKey(current.type(), new String(apiKey.getPassword()));
		service.applyTransient(working.copy());
		return true;
	}

	private void runTest() {
		testOut.setText("Testing…");
		if (!applyTemporarily()) {
			return;
		}
		new SwingWorker<ConnectionReport, Void>() {
			@Override
			protected ConnectionReport doInBackground() {
				return service.testConnection();
			}

			@Override
			protected void done() {
				try {
					ConnectionReport r = get();
					testOut.setText(r.toString() + (r.allOk() ? "\nAll checks passed." : "\nFix the failing step above."));
					fillModels(r.models());
				}
				catch (Exception e) {
					testOut.setText("Test failed: " + e.getMessage());
				}
			}
		}.execute();
	}

	private void refreshModels() {
		if (!applyTemporarily()) {
			return;
		}
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() throws Exception {
				return service.provider().listModels();
			}

			@Override
			protected void done() {
				try {
					fillModels(get());
					testOut.setText("Found " + (model.getItemCount() - 1) + " models.");
				}
				catch (Exception e) {
					Throwable t = e.getCause() != null ? e.getCause() : e;
					testOut.setText(t.getMessage());
				}
			}
		}.execute();
	}

	private void fillModels(List<String> models) {
		Object sel = model.getSelectedItem();
		model.removeAllItems();
		model.addItem("");
		models.forEach(model::addItem);
		model.setSelectedItem(sel == null ? "" : sel);
	}

	/** Tiny DocumentListener adapter. */
	private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
		private final Runnable r;

		SimpleDocListener(Runnable r) {
			this.r = r;
		}

		public void insertUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		public void removeUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}

		public void changedUpdate(javax.swing.event.DocumentEvent e) {
			r.run();
		}
	}
}

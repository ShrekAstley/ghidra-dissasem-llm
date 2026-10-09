package ghidrallm.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.swing.*;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidrallm.agent.*;
import ghidrallm.config.Settings;
import ghidrallm.llm.Usage;

/**
 * The "Local LLM RE Assistant" UI: status header, chat, proposals, architecture, knowledge, debug log.
 * All model/Ghidra work happens on the service's worker thread; this class only touches Swing on the EDT.
 */
public class AssistantPanel extends JPanel {

	private final AssistantService service;
	private final Navigator navigator;
	private final Supplier<Window> owner;
	private final ExecutorService bg = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "LocalLLM-UI");
		t.setDaemon(true);
		return t;
	});

	// header
	private final JLabel statusDot = new JLabel("○");
	private final JLabel statusText = new JLabel("Checking LM Studio…");
	private final JComboBox<String> modelBox = new JComboBox<>();
	private final JSpinner temp = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));
	private final JLabel programLabel = new JLabel("Program: –");
	private final JLabel functionLabel = new JLabel("Function: –");
	private final JLabel tokensLabel = new JLabel("tokens: –");
	private final JLabel remoteBadge = new JLabel(" ");
	private final JToggleButton expertToggle = new JToggleButton("Expert");
	private final JPanel banner = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
	private final JLabel bannerMsg = new JLabel(" ");
	private boolean updatingModelBox;
	private volatile boolean connected;

	// chat
	private final TranscriptPane transcript;
	private final JTextArea input = new JTextArea(3, 40);
	private final JButton send = new JButton("➤");
	private final JButton stop = new JButton("■ Stop");
	private final JLabel activity = new JLabel(" ");
	private final JProgressBar busyBar = new JProgressBar();
	private final JButton regen = new JButton("↻ Regenerate");
	private final JButton clearBtn = new JButton("Clear");
	private final List<JButton> workflowButtons = new java.util.ArrayList<>();

	// tabs
	private final JTabbedPane tabs = new JTabbedPane();
	private final ProposalsPanel proposalsPanel;
	private final ArchitecturePanel architecturePanel;
	private final KnowledgePanel knowledgePanel;
	private final DebugLogPanel debugPanel;
	private int lastPendingCount;
	private Usage sessionUsage = Usage.NONE;

	public AssistantPanel(AssistantService service, Navigator navigator, Supplier<Window> owner) {
		super(new BorderLayout(0, 4));
		this.service = service;
		this.navigator = navigator;
		this.owner = owner;
		setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		transcript = new TranscriptPane(navigator);
		proposalsPanel = new ProposalsPanel(service.proposals(), service.access()::program, bg,
			msg -> transcript.addSystem(msg));
		architecturePanel = new ArchitecturePanel(navigator, this::analyzeProgram, this::drill, service::stop);
		knowledgePanel = new KnowledgePanel(service::knowledge, service::programKey, navigator);
		debugPanel = new DebugLogPanel(service.log(), service.settings().maskSensitiveInLogs);

		add(buildHeader(), BorderLayout.NORTH);
		tabs.addTab("Chat", buildChat());
		tabs.addTab("Proposals", proposalsPanel);
		tabs.addTab("Architecture", architecturePanel);
		tabs.addTab("Knowledge", knowledgePanel);
		tabs.addTab("Debug Log", debugPanel);
		tabs.addChangeListener(e -> {
			if (tabs.getSelectedComponent() == knowledgePanel) {
				knowledgePanel.refresh();
			}
		});
		add(tabs, BorderLayout.CENTER);
		service.proposals().addListener(() -> SwingUtilities.invokeLater(this::updateProposalTab));
		applySettingsToUi();
		contextChanged();
		checkConnectionAsync();
		new Timer(10_000, e -> {
			// Only poll local servers; remote providers are checked on demand (rate limits, key exposure).
			if (isShowing() && !service.isBusy() && !service.settings().isRemote()) {
				checkConnectionAsync();
			}
		}).start();
	}

	// ---- layout -----------------------------------------------------------------------------

	private JComponent buildHeader() {
		JPanel p = new JPanel(new GridLayout(0, 1, 0, 2));
		JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		statusDot.setFont(statusDot.getFont().deriveFont(Font.BOLD, 16f));
		r1.add(statusDot);
		r1.add(statusText);
		r1.add(new JLabel("  Model:"));
		modelBox.setPrototypeDisplayValue("some-long-model-name-7b-instruct");
		modelBox.setToolTipText("Model used for requests. Empty = whichever model LM Studio has loaded.");
		modelBox.addActionListener(e -> {
			if (updatingModelBox) {
				return;
			}
			Object m = modelBox.getSelectedItem();
			Settings s = service.settings().copy();
			s.model = m == null || m.toString().startsWith("(") ? "" : m.toString();
			saveQuietly(s);
		});
		r1.add(modelBox);
		r1.add(new JLabel("Temp:"));
		temp.setPreferredSize(new Dimension(60, temp.getPreferredSize().height));
		temp.addChangeListener(e -> {
			Settings s = service.settings().copy();
			s.temperature = ((Number) temp.getValue()).doubleValue();
			saveQuietly(s);
		});
		r1.add(temp);
		expertToggle.setToolTipText("Show tool calls, raw output, timing and token usage");
		expertToggle.addActionListener(e -> {
			Settings s = service.settings().copy();
			s.expertMode = expertToggle.isSelected();
			saveQuietly(s);
			transcript.setExpert(s.expertMode);
		});
		JButton settingsBtn = new JButton("⚙");
		settingsBtn.setToolTipText("Settings");
		settingsBtn.addActionListener(e -> openSettings());
		r1.add(expertToggle);
		r1.add(settingsBtn);
		JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		r2.add(programLabel);
		r2.add(functionLabel);
		r2.add(tokensLabel);
		r2.add(remoteBadge);
		p.add(r1);
		p.add(r2);

		bannerMsg.setText("Start LM Studio, load a model and enable the local server (Developer tab).");
		JLabel msg = bannerMsg;
		JButton retry = new JButton("Retry Connection"), settings = new JButton("Settings");
		retry.addActionListener(e -> checkConnectionAsync());
		settings.addActionListener(e -> openSettings());
		banner.add(msg);
		banner.add(retry);
		banner.add(settings);
		banner.setBackground(Theme.mix(Theme.bg(), Theme.bad(), 0.15));
		banner.setVisible(false);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.add(p, BorderLayout.CENTER);
		wrap.add(banner, BorderLayout.SOUTH);
		return wrap;
	}

	private JComponent buildChat() {
		JPanel chat = new JPanel(new BorderLayout(0, 4));
		chat.add(transcript, BorderLayout.CENTER);
		JPanel south = new JPanel(new BorderLayout(0, 3));

		JPanel status = new JPanel(new BorderLayout(6, 0));
		busyBar.setIndeterminate(true);
		busyBar.setVisible(false);
		busyBar.setPreferredSize(new Dimension(90, 10));
		status.add(activity, BorderLayout.CENTER);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.add(busyBar);
		stop.setVisible(false);
		stop.setToolTipText("Stop the current generation / agent run");
		stop.addActionListener(e -> service.stop());
		regen.setToolTipText("Discard the last answer and ask again");
		regen.addActionListener(e -> regenerate());
		clearBtn.setToolTipText("Clear the conversation (and session memory)");
		clearBtn.addActionListener(e -> {
			service.clearConversation();
			transcript.clear();
			sessionUsage = Usage.NONE;
			tokensLabel.setText("tokens: –");
		});
		right.add(stop);
		right.add(regen);
		right.add(clearBtn);
		status.add(right, BorderLayout.EAST);
		south.add(status, BorderLayout.NORTH);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
		input.getActionMap().put("send", new AbstractAction() {
			public void actionPerformed(java.awt.event.ActionEvent e) {
				sendFromInput();
			}
		});
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "insert-break");
		JScrollPane inScroll = new JScrollPane(input);
		JPanel inRow = new JPanel(new BorderLayout(4, 0));
		inRow.add(inScroll, BorderLayout.CENTER);
		send.setToolTipText("Send (Enter). Shift+Enter for a new line.");
		send.addActionListener(e -> sendFromInput());
		inRow.add(send, BorderLayout.EAST);
		JLabel placeholder = new JLabel("Ask the program…  (e.g. “What does this function do?”, “Where is this string used?”, “Find code related to encryption”)");
		placeholder.setForeground(Theme.unknown());
		south.add(inRow, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		actions.add(workflow("Explain", "Explain the selected function", Workflows.EXPLAIN));
		actions.add(workflow("Analyze", "Full structured function analysis", Workflows.ANALYZE));
		JButton trace = new JButton("Trace");
		trace.setToolTipText("Trace where a variable's value comes from");
		trace.addActionListener(e -> traceVariable());
		actions.add(trace);
		workflowButtons.add(trace);
		actions.add(workflow("Security", "Security review of the selected function", Workflows.SECURITY));
		JButton more = new JButton("More ▾");
		more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
		actions.add(more);
		workflowButtons.add(more);
		actions.add(placeholderLabel());
		south.add(actions, BorderLayout.SOUTH);
		chat.add(south, BorderLayout.SOUTH);
		return chat;
	}

	private JLabel placeholderLabel() {
		JLabel l = new JLabel("Enter = send · Shift+Enter = newline");
		l.setForeground(Theme.unknown());
		return l;
	}

	private JButton workflow(String label, String display, String prompt) {
		JButton b = new JButton(label);
		b.setToolTipText(display);
		b.addActionListener(e -> send(display, prompt, true));
		workflowButtons.add(b);
		return b;
	}

	private JPopupMenu moreMenu() {
		JPopupMenu m = new JPopupMenu();
		m.add(item("Generate function comment", () -> send("Generate a function comment", Workflows.COMMENT, true)));
		m.add(item("Suggest names (function + variables)", () -> send("Suggest better names", Workflows.NAMES, true)));
		m.add(item("Reconstruct data types", () -> send("Reconstruct data structures", Workflows.TYPES, true)));
		m.add(item("Explain relationships (callers / callees / globals)", () -> send("Explain cross-function relationships", Workflows.RELATIONSHIPS, true)));
		m.addSeparator();
		m.add(item("Analyze Program (architecture)…", this::analyzeProgram));
		m.addSeparator();
		boolean expert = service.settings().expertMode;
		JMenuItem raw1 = item("Expert: raw decompiler output", () -> rawTool("get_function_decompile", "Raw decompiler"));
		JMenuItem raw2 = item("Expert: raw assembly", () -> rawTool("get_function_assembly", "Raw assembly"));
		JMenuItem ctx = item("Expert: agent context (last request)", () -> showText("Agent context — last request sent to the model", debugPanel.lastRequestDetail()));
		for (JMenuItem it : List.of(raw1, raw2, ctx)) {
			it.setEnabled(expert);
			m.add(it);
		}
		if (!expert) {
			JMenuItem hint = new JMenuItem("(enable Expert mode for raw views)");
			hint.setEnabled(false);
			m.add(hint);
		}
		return m;
	}

	private JMenuItem item(String label, Runnable r) {
		JMenuItem i = new JMenuItem(label);
		i.addActionListener(e -> r.run());
		return i;
	}

	// ---- actions ----------------------------------------------------------------------------

	private void sendFromInput() {
		String t = input.getText().trim();
		if (t.isEmpty() || service.isBusy()) {
			return;
		}
		input.setText("");
		send(t, t, true);
	}

	/** Public so plugin context-menu actions can trigger workflows. */
	public void send(String display, String prompt, boolean withContext) {
		if (service.isBusy()) {
			return;
		}
		tabs.setSelectedIndex(0);
		transcript.addUser(display);
		setBusy(true, "Starting…");
		service.ask(display, prompt, withContext, listener(), r -> SwingUtilities.invokeLater(() -> finished(r)));
	}

	private void regenerate() {
		if (service.isBusy() || service.lastQuestion() == null) {
			return;
		}
		transcript.removeLastAi();
		setBusy(true, "Regenerating…");
		service.regenerate(listener(), r -> SwingUtilities.invokeLater(() -> finished(r)));
	}

	public void workflowExplain() {
		send("Explain the selected function", Workflows.EXPLAIN, true);
	}

	public void workflowAnalyze() {
		send("Analyze the selected function", Workflows.ANALYZE, true);
	}

	public void workflowSecurity() {
		send("Security review of the selected function", Workflows.SECURITY, true);
	}

	public void workflowTrace() {
		traceVariable();
	}

	private void traceVariable() {
		if (service.isBusy()) {
			return;
		}
		String v = JOptionPane.showInputDialog(this,
			"Variable to trace (name as shown in the decompiler, e.g. param_1 or local_28).\nUse Expert → raw decompiler to see names.",
			"Trace value", JOptionPane.QUESTION_MESSAGE);
		if (v != null && !v.isBlank()) {
			String var = v.trim();
			send("Trace " + var + " backward to its origin", Workflows.trace(var, true), true);
		}
	}

	private AgentListener listener() {
		return new AgentListener() {
			@Override
			public void onStatus(String s) {
				SwingUtilities.invokeLater(() -> activity.setText(s));
			}

			@Override
			public void onToolCall(String name, String args) {
				SwingUtilities.invokeLater(() -> {
					activity.setText("Running tool: " + name);
					transcript.addTool(name + "(" + prettyArgs(args) + ")", args);
				});
			}

			@Override
			public void onToolResult(String name, String result, boolean error, long ms) {
				SwingUtilities.invokeLater(() -> transcript.addMeta((error ? "✖ " : "✔ ") + name + " " + ms + " ms, " + result.length() + " chars"));
			}

			@Override
			public void onAssistantText(String text) {
				SwingUtilities.invokeLater(() -> transcript.addMeta("model: " + ghidrallm.util.Text.oneLine(text, 160)));
			}

			@Override
			public void onUsage(Usage turn, Usage total) {
				SwingUtilities.invokeLater(() -> {
					sessionUsage = sessionUsage.plus(turn);
					int budget = service.settings().contextBudgetTokens;
					tokensLabel.setText("tokens: last prompt " + (turn.promptTokens() > 0 ? turn.promptTokens() : "n/a") + " / budget " + budget +
						" · session " + sessionUsage.totalTokens());
				});
			}
		};
	}

	/** key=value, key=value form of a JSON argument object, shortened. */
	static String prettyArgs(String json) {
		try {
			var o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			StringBuilder sb = new StringBuilder();
			for (var e : o.entrySet()) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString();
				sb.append(e.getKey()).append('=').append(ghidrallm.util.Text.oneLine(v, 40));
			}
			return ghidrallm.util.Text.oneLine(sb.toString(), 110);
		}
		catch (RuntimeException e) {
			return ghidrallm.util.Text.oneLine(json, 110);
		}
	}

	/** Re-probes LM Studio now (used by Retry and by tests). */
	public void checkConnectionNow() {
		checkConnectionAsync();
	}

	private void finished(AgentResult r) {
		setBusy(false, " ");
		switch (r.status()) {
			case CANCELLED -> transcript.addSystem("Stopped.");
			case ERROR -> {
				transcript.addError(r.error());
				if (r.error() != null && (r.error().contains("not reachable") || r.error().contains("LM Studio"))) {
					checkConnectionAsync();
				}
			}
			default -> {
				String meta = r.toolCalls() + " tool call" + (r.toolCalls() == 1 ? "" : "s") + " · " + String.format("%.1f s", r.elapsedMillis() / 1000.0) +
					(r.usage().totalTokens() > 0 ? " · " + r.usage().totalTokens() + " tokens" : "") +
					(r.status() == AgentResult.Status.LIMIT_REACHED ? " · limit reached" : "");
				transcript.addAi(r.answer(), service.settings().expertMode ? meta : (r.toolCalls() > 0 ? r.toolCalls() + " tool calls" : ""));
				if (service.settings().expertMode) {
					transcript.addMeta(meta);
					if (!r.hiddenReasoning().isBlank()) {
						transcript.addMeta("(model emitted a reasoning block, " + r.hiddenReasoning().length() + " chars, not displayed or relied on)");
					}
				}
			}
		}
		updateProposalTab();
		int pending = service.proposals().pending().size();
		if (pending > lastPendingCount) {
			transcript.addSystem(pending - lastPendingCount + " change proposal(s) waiting for your review in the Proposals tab. Nothing has been changed yet.");
			tabs.setSelectedIndex(1);
		}
		lastPendingCount = pending;
	}

	private void setBusy(boolean busy, String text) {
		busyBar.setVisible(busy);
		stop.setVisible(busy);
		send.setEnabled(!busy);
		regen.setEnabled(!busy);
		clearBtn.setEnabled(!busy);
		for (JButton b : workflowButtons) {
			b.setEnabled(!busy);
		}
		activity.setText(text);
	}

	private void updateProposalTab() {
		int n = service.proposals().pending().size();
		tabs.setTitleAt(1, n > 0 ? "Proposals (" + n + ")" : "Proposals");
		lastPendingCount = n;
		contextChanged();   // an applied rename changes the function name shown in the header
	}

	private void analyzeProgram() {
		if (service.isBusy()) {
			return;
		}
		if (!connected) {
			JOptionPane.showMessageDialog(this, "LM Studio is not connected.", "Analyze Program", JOptionPane.WARNING_MESSAGE);
			return;
		}
		tabs.setSelectedComponent(architecturePanel);
		architecturePanel.setRunning(true);
		setBusy(true, "Analyzing program…");
		stop.setVisible(true);
		service.analyzeProgram((cur, total, msg) -> SwingUtilities.invokeLater(() -> {
			architecturePanel.setProgress(cur, total, msg);
			activity.setText(msg);
		}), r -> SwingUtilities.invokeLater(() -> {
			architecturePanel.setRunning(false);
			setBusy(false, " ");
			if (r.status() == AgentResult.Status.COMPLETED && service.lastReport() != null) {
				architecturePanel.showReport(service.lastReport());
				transcript.addSystem("Program architecture analysis finished (" + String.format("%.0f s", r.elapsedMillis() / 1000.0) + "). See the Architecture tab.");
			}
			else if (r.status() == AgentResult.Status.CANCELLED) {
				transcript.addSystem("Program analysis stopped.");
			}
			else {
				transcript.addError(r.error() == null ? "Program analysis failed." : r.error());
			}
		}));
	}

	private void drill(ArchitectureReport.Node node) {
		tabs.setSelectedIndex(0);
		transcript.addUser("Drill down: " + node.name);
		setBusy(true, "Investigating " + node.name + "…");
		service.drillDown(node, listener(), r -> SwingUtilities.invokeLater(() -> finished(r)));
	}

	private void rawTool(String tool, String title) {
		Program p = service.access().program();
		if (p == null) {
			JOptionPane.showMessageDialog(this, "No program open.");
			return;
		}
		bg.submit(() -> {
			String out = service.runReadOnlyTool(tool, "{\"function\":\"current\"}");
			SwingUtilities.invokeLater(() -> showText(title + " — current function", out));
		});
	}

	private void showText(String title, String text) {
		JTextArea ta = new JTextArea(text, 28, 90);
		ta.setEditable(false);
		ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
		ta.setCaretPosition(0);
		JOptionPane.showMessageDialog(this, new JScrollPane(ta), title, JOptionPane.PLAIN_MESSAGE);
	}

	private void openSettings() {
		SettingsDialog d = new SettingsDialog(owner.get(), service);
		d.setVisible(true);
		if (d.wasSaved()) {
			applySettingsToUi();
			checkConnectionAsync();
		}
	}

	private void saveQuietly(Settings s) {
		try {
			service.updateSettings(s);
		}
		catch (java.io.IOException e) {
			transcript.addError("Could not save settings: " + e.getMessage());
		}
	}

	private void updateRemoteBadge() {
		Settings s = service.settings();
		if (s.isRemote()) {
			String host = ghidrallm.llm.EndpointPolicy.hostOf(s.endpoint);
			remoteBadge.setText("☁ REMOTE: " + host);
			remoteBadge.setForeground(Theme.bad());
			remoteBadge.setToolTipText("Program data (code, strings, memory) is sent to " + host + " when you ask questions.");
		}
		else {
			remoteBadge.setText("🔒 local");
			remoteBadge.setForeground(Theme.ok());
			remoteBadge.setToolTipText("All analysis stays on this machine.");
		}
	}

	private void applySettingsToUi() {
		Settings s = service.settings();
		updateRemoteBadge();
		expertToggle.setSelected(s.expertMode);
		transcript.setExpert(s.expertMode);
		temp.setValue(s.temperature);
		debugPanel.setMask(s.maskSensitiveInLogs);
		updatingModelBox = true;
		if (modelBox.getItemCount() == 0) {
			modelBox.addItem(s.model.isBlank() ? "(auto: loaded model)" : s.model);
		}
		updatingModelBox = false;
	}

	// ---- status / context -------------------------------------------------------------------

	private void checkConnectionAsync() {
		bg.submit(() -> {
			AssistantService.Status st = service.checkConnection();
			SwingUtilities.invokeLater(() -> showStatus(st));
		});
	}

	private String providerName() {
		return switch (service.settings().providerType) {
			case "ANTHROPIC" -> "Claude (Anthropic)";
			case "OPENAI_COMPATIBLE" -> "OpenAI-compatible provider";
			default -> "LM Studio";
		};
	}

	private void showStatus(AssistantService.Status st) {
		boolean remote = service.settings().isRemote();
		String name = providerName();
		connected = st.connected() && !st.model().isBlank() && st.message().isEmpty();
		Color c = connected ? Theme.ok() : st.connected() ? Theme.possible() : Theme.bad();
		statusDot.setForeground(c);
		statusDot.setText(st.connected() ? "●" : "○");
		if (connected) {
			statusText.setText(name + " Connected");
		}
		else if (st.connected()) {
			statusText.setText(name + ": " + st.message());
		}
		else {
			statusText.setText(name + " Disconnected");
		}
		bannerMsg.setText(remote ? "<html>" + ghidrallm.util.Text.escapeHtml(st.message()) + "</html>"
				: "Start LM Studio, load a model and enable the local server (Developer tab).");
		banner.setVisible(!st.connected());
		updateRemoteBadge();
		statusDot.setToolTipText(st.message().isEmpty() ? service.settings().endpoint : st.message());
		updatingModelBox = true;
		modelBox.removeAllItems();
		modelBox.addItem("(auto: loaded model)");
		for (String m : st.models()) {
			modelBox.addItem(m);
		}
		String cur = service.settings().model;
		modelBox.setSelectedItem(cur.isBlank() ? "(auto: loaded model)" : cur);
		updatingModelBox = false;
		revalidate();
	}

	/** Called by the plugin when the program/cursor changes. */
	public void contextChanged() {
		Program p = service.access().program();
		Address a = service.access().currentAddress();
		programLabel.setText("Program: " + (p == null ? "–" : p.getName()));
		String fn = "–";
		if (p != null && a != null) {
			Function f = p.getFunctionManager().getFunctionContaining(a);
			fn = f != null ? f.getName() + " @ " + f.getEntryPoint() : "(not in a function) " + a;
		}
		functionLabel.setText("Function: " + fn);
	}

	/** A different program became active: conversation memory and proposals belong to the old one. */
	public void programChanged(Program p) {
		contextChanged();
		String key = p == null ? null : ghidrallm.knowledge.ProgramKeys.of(p);
		if (lastProgramKey != null && key != null && !key.equals(lastProgramKey)) {
			service.clearConversation();
			service.proposals().clearAll();
			transcript.clear();
			transcript.addSystem("Switched to " + p.getName() + ". Conversation and pending proposals were cleared.");
		}
		if (key != null) {
			lastProgramKey = key;
		}
	}

	private String lastProgramKey;

	public void dispose() {
		bg.shutdownNow();
	}
}

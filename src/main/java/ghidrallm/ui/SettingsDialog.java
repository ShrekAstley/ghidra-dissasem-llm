package ghidrallm.ui;

import java.awt.*;
import java.util.List;

import javax.swing.*;

import ghidrallm.agent.AssistantService;
import ghidrallm.config.Settings;
import ghidrallm.llm.ConnectionReport;

/** Modal settings editor with a three-step Test Connection. */
public class SettingsDialog extends JDialog {

	private final AssistantService service;
	private final Settings working;
	private final Settings original;
	private boolean saved;

	private final JTextField endpoint = new JTextField(30);
	private final JComboBox<String> model = new JComboBox<>();
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
	private final JCheckBox allowRemote = new JCheckBox("Allow non-localhost endpoint (e.g. LM Studio on another LAN machine)");
	private final JCheckBox proposals = new JCheckBox("Let the model queue change proposals (never applied without approval)");
	private final JCheckBox knowledgeTools = new JCheckBox("Let the model read/write the local knowledge database");
	private final JCheckBox persist = new JCheckBox("Persist analysis knowledge locally (SQLite)");
	private final JCheckBox expert = new JCheckBox("Expert mode (tool calls, raw output, timing, token usage)");
	private final JCheckBox autoCtx = new JCheckBox("Attach selected-function context to questions");
	private final JCheckBox maskLogs = new JCheckBox("Mask program data in debug log by default");
	private final JTextArea testOut = new JTextArea(6, 40);

	public SettingsDialog(Window owner, AssistantService service) {
		super(owner, "Local LLM RE Assistant — Settings", ModalityType.APPLICATION_MODAL);
		this.service = service;
		this.original = service.settings();
		this.working = original.copy();
		model.setEditable(true);
		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 6, 3, 6);
		c.anchor = GridBagConstraints.WEST;
		int[] row = {0};
		addRow(form, c, row, "LM Studio URL", endpoint, "OpenAI-compatible local endpoint. Default http://localhost:1234/v1");
		addRow(form, c, row, "Model", model, "Leave empty to use the model LM Studio has loaded");
		addRow(form, c, row, "Temperature", temperature, "Low values (0.1–0.3) work best for analysis");
		addRow(form, c, row, "Max output tokens", maxOut, "Upper bound per model reply");
		addRow(form, c, row, "Context budget (tokens)", ctxBudget, "Must be ≤ the context length loaded in LM Studio; 16384+ recommended");
		addRow(form, c, row, "Agent tool-call limit", toolLimit, "Max tool calls per question");
		addRow(form, c, row, "Agent step limit", steps, "Max model round-trips per question");
		addRow(form, c, row, "Max identical repeats", repeats, "Same tool+arguments beyond this are blocked");
		addRow(form, c, row, "Max tool result chars", resultChars, "Longer results are truncated before the model sees them");
		addRow(form, c, row, "Request timeout (s)", timeout, "Per HTTP request to LM Studio");
		addRow(form, c, row, "Agent time limit (s)", agentTimeout, "Whole question, including tool calls");
		addRow(form, c, row, "Tool protocol", toolMode, "AUTO tries native tool calling, then falls back to <tool_call> text");
		addRow(form, c, row, "Program-analysis functions", analysisFns, "How many key functions “Analyze Program” summarizes");
		for (JCheckBox cb : List.of(allowRemote, proposals, knowledgeTools, persist, autoCtx, expert, maskLogs)) {
			c.gridx = 0;
			c.gridy = row[0]++;
			c.gridwidth = 2;
			c.fill = GridBagConstraints.NONE;
			form.add(cb, c);
		}
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
		add(new JScrollPane(form), BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);
		load(working);
		test.addActionListener(e -> runTest());
		refresh.addActionListener(e -> refreshModels());
		reset.addActionListener(e -> load(new Settings()));
		ok.addActionListener(e -> {
			try {
				store();
				service.updateSettings(working);
				saved = true;
				dispose();
			}
			catch (Exception ex) {
				JOptionPane.showMessageDialog(this, "Could not save settings: " + ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
			}
		});
		cancel.addActionListener(e -> dispose());
		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosed(java.awt.event.WindowEvent e) {
				if (!saved) {
					service.applyTransient(original);   // discard any test-only changes
				}
			}
		});
		setPreferredSize(new Dimension(640, 780));
		pack();
		setLocationRelativeTo(owner);
	}

	public boolean wasSaved() {
		return saved;
	}

	private void addRow(JPanel p, GridBagConstraints c, int[] row, String label, JComponent comp, String tip) {
		c.gridy = row[0]++;
		c.gridx = 0;
		c.gridwidth = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		p.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = comp instanceof JTextField || comp instanceof JComboBox ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
		p.add(comp, c);
		comp.setToolTipText(tip);
		c.gridy = row[0]++;
		JLabel t = new JLabel(tip);
		t.setForeground(Theme.unknown());
		t.setFont(t.getFont().deriveFont(t.getFont().getSize2D() - 1.5f));
		c.insets = new Insets(0, 6, 6, 6);
		c.fill = GridBagConstraints.NONE;
		p.add(t, c);
		c.insets = new Insets(3, 6, 3, 6);
	}

	private void load(Settings s) {
		endpoint.setText(s.endpoint);
		model.removeAllItems();
		model.addItem("");
		if (!s.model.isBlank()) {
			model.addItem(s.model);
		}
		model.setSelectedItem(s.model);
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
		allowRemote.setSelected(s.allowNonLoopbackEndpoint);
		proposals.setSelected(s.allowProposalTools);
		knowledgeTools.setSelected(s.allowKnowledgeTools);
		persist.setSelected(s.persistKnowledge);
		expert.setSelected(s.expertMode);
		autoCtx.setSelected(s.includeContextOnSelection);
		maskLogs.setSelected(s.maskSensitiveInLogs);
	}

	private void store() {
		working.endpoint = endpoint.getText().trim();
		Object m = model.getSelectedItem();
		working.model = m == null ? "" : m.toString().trim();
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
		working.allowNonLoopbackEndpoint = allowRemote.isSelected();
		working.allowProposalTools = proposals.isSelected();
		working.allowKnowledgeTools = knowledgeTools.isSelected();
		working.persistKnowledge = persist.isSelected();
		working.expertMode = expert.isSelected();
		working.includeContextOnSelection = autoCtx.isSelected();
		working.maskSensitiveInLogs = maskLogs.isSelected();
	}

	/** Applies the dialog's endpoint to a temporary service view for testing before saving. */
	private void applyTemporarily() {
		store();
		service.applyTransient(working.copy());
	}

	private void runTest() {
		testOut.setText("Testing…");
		applyTemporarily();
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
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() throws Exception {
				applyTemporarily();
				return service.provider().listModels();
			}

			@Override
			protected void done() {
				try {
					fillModels(get());
					testOut.setText("Found " + model.getItemCount() + " entries.");
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
}

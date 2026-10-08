package ghidrallm.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import ghidrallm.log.DebugLog;

/** Local debug log: LLM requests, tool calls/results, timing, errors. Sensitive rows are marked. */
public class DebugLogPanel extends JPanel {

	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	private final DebugLog log;
	private List<DebugLog.Entry> rows = List.of();
	private final JComboBox<String> filter = new JComboBox<>(new String[] {"All", "LLM_REQUEST", "LLM_RESPONSE", "TOOL_CALL", "TOOL_RESULT", "AGENT", "TIMING", "CHANGE", "ERROR", "INFO"});
	private final JCheckBox mask = new JCheckBox("Mask program data");
	private final JTextArea detail = new JTextArea();
	private final AbstractTableModel model = new AbstractTableModel() {
		final String[] cols = {"Time", "Type", "🔒", "Summary"};

		public int getRowCount() {
			return rows.size();
		}

		public int getColumnCount() {
			return cols.length;
		}

		public String getColumnName(int c) {
			return cols[c];
		}

		public Object getValueAt(int r, int c) {
			DebugLog.Entry e = rows.get(r);
			return switch (c) {
				case 0 -> FMT.format(e.time());
				case 1 -> e.category();
				case 2 -> e.sensitive() ? "🔒" : "";
				default -> e.summary();
			};
		}
	};
	private final JTable table = new JTable(model);

	public DebugLogPanel(DebugLog log, boolean initialMask) {
		super(new BorderLayout(0, 4));
		this.log = log;
		mask.setSelected(initialMask);
		table.getColumnModel().getColumn(0).setPreferredWidth(110);
		table.getColumnModel().getColumn(1).setPreferredWidth(110);
		table.getColumnModel().getColumn(2).setMaxWidth(30);
		table.getColumnModel().getColumn(3).setPreferredWidth(500);
		detail.setEditable(false);
		detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
		table.getSelectionModel().addListSelectionListener(e -> showDetail());
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
		split.setResizeWeight(0.5);
		add(split, BorderLayout.CENTER);
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		JButton copy = new JButton("Copy log"), export = new JButton("Export…"), clear = new JButton("Clear");
		top.add(new JLabel("Filter:"));
		top.add(filter);
		top.add(mask);
		top.add(copy);
		top.add(export);
		top.add(clear);
		add(top, BorderLayout.NORTH);
		add(new JLabel("<html>Local only. 🔒 marks entries containing program data (code, strings, memory). “Mask” hides them in view and in copies/exports.</html>"), BorderLayout.SOUTH);
		filter.addActionListener(e -> reload());
		mask.addActionListener(e -> showDetail());
		clear.addActionListener(e -> {
			log.clear();
			reload();
		});
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(log.export(mask.isSelected())), null));
		export.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			fc.setSelectedFile(new java.io.File("local-llm-debug.log"));
			if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
				try {
					Files.writeString(fc.getSelectedFile().toPath(), log.export(mask.isSelected()));
				}
				catch (IOException ex) {
					JOptionPane.showMessageDialog(this, "Could not write file: " + ex.getMessage());
				}
			}
		});
		log.addListener(e -> SwingUtilities.invokeLater(this::reload));
		reload();
	}

	public void setMask(boolean m) {
		mask.setSelected(m);
		showDetail();
	}

	/** Detail of the most recent LLM request: what the model was actually sent. */
	public String lastRequestDetail() {
		List<DebugLog.Entry> all = log.snapshot();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i).category() == DebugLog.Category.LLM_REQUEST) {
				return all.get(i).detail();
			}
		}
		return "(no request yet)";
	}

	private void reload() {
		String f = (String) filter.getSelectedItem();
		rows = log.snapshot().stream().filter(e -> f == null || f.equals("All") || e.category().name().equals(f)).collect(Collectors.toList());
		model.fireTableDataChanged();
		if (!rows.isEmpty()) {
			table.scrollRectToVisible(table.getCellRect(rows.size() - 1, 0, true));
		}
	}

	private void showDetail() {
		int r = table.getSelectedRow();
		if (r < 0 || r >= rows.size()) {
			detail.setText("");
			return;
		}
		DebugLog.Entry e = rows.get(r);
		detail.setText(e.sensitive() && mask.isSelected() ? "<masked: " + e.detail().length() + " chars of program data>" : e.detail());
		detail.setCaretPosition(0);
	}
}

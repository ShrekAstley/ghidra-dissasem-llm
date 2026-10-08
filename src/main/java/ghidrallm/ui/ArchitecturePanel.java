package ghidrallm.ui;

import java.awt.*;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import ghidrallm.agent.ArchitectureReport;

/** Program architecture tree with drill-down. */
public class ArchitecturePanel extends JPanel {

	private final JTree tree = new JTree(new DefaultMutableTreeNode("Run “Analyze Program” to build an architecture overview"));
	private final JTextArea detail = new JTextArea();
	private final JButton analyze = new JButton("Analyze Program");
	private final JButton drill = new JButton("Drill down");
	private final JProgressBar progress = new JProgressBar();
	private final JLabel progressText = new JLabel(" ");
	private final Navigator navigator;
	private ArchitectureReport report;

	public ArchitecturePanel(Navigator navigator, Runnable onAnalyze, Consumer<ArchitectureReport.Node> onDrill, Runnable onStop) {
		super(new BorderLayout(0, 4));
		this.navigator = navigator;
		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		tree.setRootVisible(true);
		tree.addTreeSelectionListener(e -> showSelection());
		tree.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					openSelected();
				}
			}
		});
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tree), new JScrollPane(detail));
		split.setResizeWeight(0.6);
		add(split, BorderLayout.CENTER);
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		top.add(analyze);
		top.add(drill);
		top.add(progress);
		top.add(progressText);
		progress.setVisible(false);
		drill.setEnabled(false);
		add(top, BorderLayout.NORTH);
		analyze.addActionListener(e -> {
			if (analyze.getText().equals("Stop")) {
				onStop.run();
			}
			else {
				onAnalyze.run();
			}
		});
		drill.addActionListener(e -> {
			ArchitectureReport.Node n = selectedNode();
			if (n != null) {
				onDrill.accept(n);
			}
		});
		detail.setText("Progressive analysis: Ghidra facts → per-function summaries → subsystem synthesis.\n" +
			"Only compact summaries are sent to the local model, never the whole binary.\n\n" +
			"Double-click a function entry to jump to it. Select a subsystem and press “Drill down” to investigate it in the chat.");
	}

	public void setRunning(boolean running) {
		analyze.setText(running ? "Stop" : "Analyze Program");
		progress.setVisible(running);
		if (!running) {
			progressText.setText(" ");
		}
	}

	public void setProgress(int cur, int total, String msg) {
		progress.setMaximum(Math.max(1, total));
		progress.setValue(cur);
		progressText.setText(msg);
	}

	public void showReport(ArchitectureReport r) {
		this.report = r;
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Program Architecture");
		for (ArchitectureReport.Node n : r.root.children) {
			root.add(build(n));
		}
		tree.setModel(new DefaultTreeModel(root));
		for (int i = 0; i < tree.getRowCount() && i < 40; i++) {
			tree.expandRow(i);
		}
		detail.setText(r.overview.isBlank() ? "(no overview provided)" : r.overview);
		drill.setEnabled(root.getChildCount() > 0);
	}

	private DefaultMutableTreeNode build(ArchitectureReport.Node n) {
		DefaultMutableTreeNode t = new DefaultMutableTreeNode(n);
		for (String f : n.functions) {
			t.add(new DefaultMutableTreeNode(f));
		}
		for (ArchitectureReport.Node c : n.children) {
			t.add(build(c));
		}
		return t;
	}

	private ArchitectureReport.Node selectedNode() {
		TreePath p = tree.getSelectionPath();
		if (p == null) {
			return null;
		}
		Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
		return o instanceof ArchitectureReport.Node n ? n : null;
	}

	private void showSelection() {
		TreePath p = tree.getSelectionPath();
		if (p == null) {
			return;
		}
		Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
		if (o instanceof ArchitectureReport.Node n) {
			detail.setText(n.name + "  [" + n.confidence + "]\n\n" + n.description + "\n\nFunctions: " +
				(n.functions.isEmpty() ? "(none listed)" : String.join(", ", n.functions)) +
				"\n\nModel-generated grouping — verify with the tools before relying on it.");
			detail.setCaretPosition(0);
		}
	}

	private void openSelected() {
		TreePath p = tree.getSelectionPath();
		if (p == null) {
			return;
		}
		Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
		if (o instanceof String s) {
			int at = s.lastIndexOf('@');
			navigator.goTo(at >= 0 ? s.substring(at + 1) : s);
		}
	}

	@Override
	public String toString() {
		return report == null ? "" : report.toTreeText();
	}
}

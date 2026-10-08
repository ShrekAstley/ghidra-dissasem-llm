package ghidrallm.ui;

import java.awt.*;
import java.util.List;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import ghidra.program.model.listing.Program;
import ghidrallm.changes.*;
import ghidrallm.util.Text;

/** Review queue: preview → approve / edit / reject. Nothing is applied without a click here. */
public class ProposalsPanel extends JPanel {

	private final ProposalManager manager;
	private final java.util.function.Supplier<Program> program;
	private final java.util.function.Consumer<String> chatNotice;
	private final java.util.concurrent.ExecutorService exec;
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JTextArea detail = new JTextArea();
	private final JButton apply = new JButton("Apply");
	private final JButton reject = new JButton("Reject");
	private final JButton edit = new JButton("Edit…");
	private final JButton applyAll = new JButton("Apply all safe");
	private final JButton clear = new JButton("Clear finished");
	private final JLabel hint = new JLabel(" ");

	private class Model extends AbstractTableModel {
		List<ChangeProposal> rows = List.of();
		final String[] cols = {"#", "Type", "Change", "Confidence", "Status"};

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
			ChangeProposal p = rows.get(r);
			return switch (c) {
				case 0 -> p.id();
				case 1 -> p.kind().name().toLowerCase().replace('_', ' ');
				case 2 -> p.title();
				case 3 -> p.confidence();
				default -> p.status();
			};
		}
	}

	public ProposalsPanel(ProposalManager manager, java.util.function.Supplier<Program> program,
			java.util.concurrent.ExecutorService exec, java.util.function.Consumer<String> chatNotice) {
		super(new BorderLayout(0, 4));
		this.manager = manager;
		this.program = program;
		this.exec = exec;
		this.chatNotice = chatNotice;
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getColumnModel().getColumn(0).setMaxWidth(50);
		table.getColumnModel().getColumn(1).setPreferredWidth(110);
		table.getColumnModel().getColumn(2).setPreferredWidth(380);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showDetail();
			}
		});
		detail.setEditable(false);
		detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
		split.setResizeWeight(0.45);
		add(split, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		buttons.add(apply);
		buttons.add(edit);
		buttons.add(reject);
		buttons.add(applyAll);
		buttons.add(clear);
		JPanel south = new JPanel(new BorderLayout());
		south.add(buttons, BorderLayout.CENTER);
		south.add(hint, BorderLayout.SOUTH);
		add(south, BorderLayout.SOUTH);
		apply.addActionListener(e -> approveSelected());
		reject.addActionListener(e -> {
			ChangeProposal p = selected();
			if (p != null) {
				manager.reject(p.id());
			}
		});
		edit.addActionListener(e -> editSelected());
		applyAll.addActionListener(e -> applyAllSafe());
		clear.addActionListener(e -> manager.clearResolved());
		manager.addListener(() -> SwingUtilities.invokeLater(this::refresh));
		refresh();
	}

	private ChangeProposal selected() {
		int r = table.getSelectedRow();
		return r < 0 || r >= model.rows.size() ? null : model.rows.get(r);
	}

	public int pendingCount() {
		return manager.pending().size();
	}

	private void refresh() {
		String selId = selected() == null ? null : selected().id();
		model.rows = manager.all();
		model.fireTableDataChanged();
		if (selId != null) {
			for (int i = 0; i < model.rows.size(); i++) {
				if (model.rows.get(i).id().equals(selId)) {
					table.setRowSelectionInterval(i, i);
				}
			}
		}
		else if (!model.rows.isEmpty()) {
			int firstPending = 0;
			for (int i = 0; i < model.rows.size(); i++) {
				if (model.rows.get(i).status() == ChangeProposal.Status.PENDING) {
					firstPending = i;
					break;
				}
			}
			table.setRowSelectionInterval(firstPending, firstPending);
		}
		showDetail();
	}

	private void showDetail() {
		ChangeProposal p = selected();
		boolean pending = p != null && p.status() == ChangeProposal.Status.PENDING;
		apply.setEnabled(pending);
		reject.setEnabled(pending);
		edit.setEnabled(pending && p.editableText() != null);
		applyAll.setEnabled(manager.pending().size() > 0);
		if (p == null) {
			detail.setText("No proposals yet.\n\nWhen the assistant suggests renames, comments, signatures or types they appear here for review. " +
				"Nothing changes in your Ghidra database until you press Apply. Every apply is one undoable step (Edit → Undo).");
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(p.title()).append("\n\n");
		Program prog = program.get();
		if (prog != null && !prog.isClosed()) {
			try {
				sb.append(p.preview(prog)).append("\n\n");
			}
			catch (RuntimeException e) {
				sb.append("(preview unavailable)\n\n");
			}
		}
		if (!p.reason().isBlank()) {
			sb.append("Reason: ").append(p.reason()).append('\n');
		}
		if (!p.confidence().isBlank()) {
			sb.append("Model confidence: ").append(p.confidence()).append(" (unverified model claim)\n");
		}
		if (pending) {
			List<Problem> probs = manager.validate(p);
			for (Problem pr : probs) {
				sb.append(switch (pr.severity()) {
					case ERROR -> "✖ Blocked: ";
					case OVERWRITE -> "⚠ Overwrites analyst data (will ask to confirm): ";
					case WARNING -> "⚠ ";
				}).append(pr.message()).append('\n');
			}
		}
		else {
			sb.append("Status: ").append(p.status()).append(p.resultMessage().isEmpty() ? "" : " — " + p.resultMessage()).append('\n');
		}
		detail.setText(sb.toString());
		detail.setCaretPosition(0);
	}

	private void approveSelected() {
		ChangeProposal p = selected();
		if (p == null) {
			return;
		}
		runApprove(p, false);
	}

	private void runApprove(ChangeProposal p, boolean allowOverwrite) {
		apply.setEnabled(false);
		exec.submit(() -> {
			ProposalManager.Result r = manager.approve(p.id(), allowOverwrite);
			SwingUtilities.invokeLater(() -> {
				if (r.needsConfirmation()) {
					StringBuilder sb = new StringBuilder("This change would overwrite analyst-authored data:\n\n");
					for (Problem pr : r.problems()) {
						if (pr.severity() == Problem.Severity.OVERWRITE) {
							sb.append(" • ").append(pr.message()).append('\n');
						}
					}
					sb.append("\nApply anyway? (You can undo with Edit → Undo.)");
					int ans = JOptionPane.showConfirmDialog(this, sb.toString(), "Confirm overwrite", JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE);
					if (ans == JOptionPane.YES_OPTION) {
						runApprove(p, true);
					}
					else {
						showDetail();
					}
					return;
				}
				hint.setText(r.message());
				chatNotice.accept((r.success() ? "Applied: " : "Not applied: ") + Text.oneLine(r.message(), 200));
				showDetail();
			});
		});
	}

	private void applyAllSafe() {
		List<ChangeProposal> todo = manager.pending();
		exec.submit(() -> {
			int ok = 0, skipped = 0;
			for (ChangeProposal p : todo) {
				List<Problem> probs = manager.validate(p);
				if (probs.stream().anyMatch(x -> x.severity() != Problem.Severity.WARNING)) {
					skipped++;
					continue;
				}
				if (manager.approve(p.id(), false).success()) {
					ok++;
				}
				else {
					skipped++;
				}
			}
			int fok = ok, fskip = skipped;
			SwingUtilities.invokeLater(() -> {
				hint.setText("Applied " + fok + ", left " + fskip + " for individual review (overwrites/blocked).");
				chatNotice.accept("Applied " + fok + " proposal(s); " + fskip + " need individual review.");
			});
		});
	}

	private void editSelected() {
		ChangeProposal p = selected();
		if (p == null || p.editableText() == null) {
			return;
		}
		JTextArea ta = new JTextArea(p.editableText(), Math.min(18, Math.max(3, p.editableText().split("\n").length + 1)), 56);
		ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
		int ans = JOptionPane.showConfirmDialog(this, new JScrollPane(ta), "Edit proposal " + p.id(), JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);
		if (ans == JOptionPane.OK_OPTION) {
			try {
				p.applyEdit(ta.getText());
				hint.setText("Edited. Review the preview, then Apply.");
			}
			catch (ChangeException e) {
				JOptionPane.showMessageDialog(this, e.getMessage(), "Cannot use edit", JOptionPane.WARNING_MESSAGE);
			}
			showDetail();
			model.fireTableDataChanged();
		}
	}
}

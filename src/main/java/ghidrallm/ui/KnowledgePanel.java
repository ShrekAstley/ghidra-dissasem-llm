package ghidrallm.ui;

import java.awt.*;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.knowledge.Note;

/** Browse / search / annotate the local analysis database for the current binary. */
public class KnowledgePanel extends JPanel {

	private final Supplier<KnowledgeStore> store;
	private final Supplier<String> programKey;
	private final JTextField search = new JTextField(18);
	private final JLabel info = new JLabel(" ");
	private List<Note> rows = List.of();
	private final AbstractTableModel model = new AbstractTableModel() {
		final String[] cols = {"Kind", "Source", "Subject", "Address", "Confidence", "Note"};

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
			Note n = rows.get(r);
			return switch (c) {
				case 0 -> n.kind();
				case 1 -> n.source();
				case 2 -> n.subject();
				case 3 -> n.address() == null ? "" : n.address();
				case 4 -> n.confidence() == null ? "" : n.confidence();
				default -> n.text();
			};
		}
	};
	private final JTable table = new JTable(model);

	public KnowledgePanel(Supplier<KnowledgeStore> store, Supplier<String> programKey, Navigator nav) {
		super(new BorderLayout(0, 4));
		this.store = store;
		this.programKey = programKey;
		table.getColumnModel().getColumn(0).setPreferredWidth(110);
		table.getColumnModel().getColumn(1).setPreferredWidth(70);
		table.getColumnModel().getColumn(5).setPreferredWidth(420);
		table.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				int r = table.getSelectedRow();
				if (e.getClickCount() == 2 && r >= 0 && rows.get(r).address() != null) {
					nav.goTo(rows.get(r).address());
				}
			}
		});
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		JButton refresh = new JButton("Refresh");
		JButton add = new JButton("Add note…");
		JButton del = new JButton("Delete");
		top.add(new JLabel("Search:"));
		top.add(search);
		top.add(refresh);
		top.add(add);
		top.add(del);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
		JLabel note = new JLabel("<html>Stored locally in a SQLite file on this machine. AI-sourced notes are unverified hypotheses; APPROVED notes record changes you accepted.</html>");
		JPanel south = new JPanel(new BorderLayout());
		south.add(info, BorderLayout.NORTH);
		south.add(note, BorderLayout.SOUTH);
		add(south, BorderLayout.SOUTH);
		refresh.addActionListener(e -> refresh());
		search.addActionListener(e -> refresh());
		del.addActionListener(e -> {
			int r = table.getSelectedRow();
			KnowledgeStore k = store.get();
			if (r >= 0 && k != null) {
				try {
					k.delete(rows.get(r).id());
				}
				catch (SQLException ex) {
					info.setText("Delete failed: " + ex.getMessage());
				}
				refresh();
			}
		});
		add.addActionListener(e -> {
			KnowledgeStore k = store.get();
			String key = programKey.get();
			if (k == null || key == null) {
				info.setText("Knowledge store is disabled or no program is open.");
				return;
			}
			JTextField subject = new JTextField(24), addr = new JTextField(12);
			JTextArea text = new JTextArea(5, 40);
			JPanel form = new JPanel(new GridLayout(0, 1, 2, 2));
			form.add(new JLabel("Subject (e.g. function name)"));
			form.add(subject);
			form.add(new JLabel("Address (optional)"));
			form.add(addr);
			form.add(new JLabel("Note"));
			form.add(new JScrollPane(text));
			if (JOptionPane.showConfirmDialog(this, form, "Add user note", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION &&
				!text.getText().isBlank()) {
				try {
					k.add(key, Note.Kind.USER_NOTE, subject.getText().isBlank() ? "note" : subject.getText(),
						addr.getText().isBlank() ? null : addr.getText().trim(), text.getText().trim(), Note.Source.USER, null);
				}
				catch (SQLException ex) {
					info.setText("Save failed: " + ex.getMessage());
				}
				refresh();
			}
		});
	}

	public void refresh() {
		KnowledgeStore k = store.get();
		String key = programKey.get();
		if (k == null) {
			rows = List.of();
			info.setText("Persistent knowledge is disabled in Settings.");
		}
		else if (key == null) {
			rows = List.of();
			info.setText("No program open.");
		}
		else {
			try {
				rows = search.getText().isBlank() ? k.list(key, null, 500) : k.search(key, search.getText().trim(), 500);
				info.setText(rows.size() + " note(s) for " + key.split("\\|")[0]);
			}
			catch (SQLException e) {
				rows = List.of();
				info.setText("Could not read knowledge store: " + e.getMessage());
			}
		}
		model.fireTableDataChanged();
	}
}

package ghidrallm.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import ghidrallm.util.Text;

/** Scrollable HTML transcript: user/AI bubbles, tool activity (expert mode), system notices, errors. */
public class TranscriptPane extends JPanel {

	private enum Kind { USER, AI, SYSTEM, ERROR, TOOL, META }

	private record Entry(Kind kind, String text, String extra) {}

	private final List<Entry> entries = new ArrayList<>();
	private final JEditorPane pane = new JEditorPane();
	private final JScrollPane scroll;
	private final Navigator navigator;
	private boolean expert;

	public TranscriptPane(Navigator navigator) {
		super(new BorderLayout());
		this.navigator = navigator;
		pane.setEditable(false);
		pane.setEditorKit(new HTMLEditorKit());
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.FALSE);
		pane.addHyperlinkListener(e -> {
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null &&
				e.getDescription().startsWith(MarkdownRenderer.GOTO)) {
				navigator.goTo(e.getDescription().substring(MarkdownRenderer.GOTO.length()));
			}
		});
		scroll = new JScrollPane(pane);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);
		render();
	}

	public void setExpert(boolean e) {
		this.expert = e;
		render();
	}

	public void clear() {
		entries.clear();
		render();
	}

	public void addUser(String t) {
		add(new Entry(Kind.USER, t, null));
	}

	public void addAi(String t, String meta) {
		add(new Entry(Kind.AI, t, meta));
	}

	public void addSystem(String t) {
		add(new Entry(Kind.SYSTEM, t, null));
	}

	public void addError(String t) {
		add(new Entry(Kind.ERROR, t, null));
	}

	/** Expert-mode tool trace line. */
	public void addTool(String t, String detail) {
		add(new Entry(Kind.TOOL, t, detail));
	}

	public void addMeta(String t) {
		add(new Entry(Kind.META, t, null));
	}

	/** Removes the last AI entry (used by regenerate). */
	public void removeLastAi() {
		for (int i = entries.size() - 1; i >= 0; i--) {
			Kind k = entries.get(i).kind();
			if (k == Kind.AI || k == Kind.TOOL || k == Kind.META) {
				entries.remove(i);
				if (k == Kind.AI) {
					break;
				}
			}
			else if (k == Kind.USER) {
				break;
			}
		}
		render();
	}

	private void add(Entry e) {
		entries.add(e);
		render();
	}

	public String plainText() {
		StringBuilder sb = new StringBuilder();
		for (Entry e : entries) {
			sb.append('[').append(e.kind()).append("] ").append(e.text()).append("\n\n");
		}
		return sb.toString();
	}

	private void render() {
		boolean atBottom = true;
		JScrollBar bar = scroll.getVerticalScrollBar();
		final int oldValue = bar == null ? 0 : bar.getValue();
		if (bar != null && bar.getMaximum() > 0) {
			atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
		}
		Color fg = Theme.fg(), bg = Theme.bg();
		Color userBg = Theme.mix(bg, Theme.likely(), 0.12), aiBg = Theme.mix(bg, fg, 0.05), sysFg = Theme.unknown();
		Font f = UIManager.getFont("Label.font");
		int size = f == null ? 13 : f.getSize();
		StyleSheet css = ((HTMLEditorKit) pane.getEditorKit()).getStyleSheet();
		StringBuilder h = new StringBuilder("<html><head><style>");
		h.append("body{font-family:").append(f == null ? "SansSerif" : f.getFamily()).append(";font-size:").append(size)
				.append("pt;color:").append(Theme.hex(fg)).append(";margin:6px;}");
		h.append("p{margin:2px 0;} ul,ol{margin:2px 0 2px 18px;} h3,h4,h5{margin:6px 0 2px 0;}");
		h.append("pre{font-family:monospaced;font-size:").append(size - 1).append("pt;background:").append(Theme.hex(Theme.mix(bg, fg, 0.08)))
				.append(";margin:3px 0;padding:4px;}");
		h.append("code{font-family:monospaced;} a{color:").append(Theme.hex(Theme.likely())).append(";}");
		h.append(".who{font-size:").append(size - 2).append("pt;color:").append(Theme.hex(sysFg)).append(";}");
		h.append("</style></head><body>");
		if (entries.isEmpty()) {
			h.append("<p class=\"who\">Ask about the selected function, or use a one-click action below. ")
					.append("The assistant inspects Ghidra with tools and cites evidence; it never changes your program without your approval.</p>");
		}
		for (Entry e : entries) {
			switch (e.kind()) {
				case USER -> h.append("<table width=\"100%\" cellpadding=\"6\" cellspacing=\"0\"><tr><td bgcolor=\"").append(Theme.hex(userBg))
						.append("\"><span class=\"who\">YOU</span><br>").append(Text.escapeHtml(e.text()).replace("\n", "<br>")).append("</td></tr></table><br>");
				case AI -> {
					h.append("<table width=\"100%\" cellpadding=\"6\" cellspacing=\"0\"><tr><td bgcolor=\"").append(Theme.hex(aiBg))
							.append("\"><span class=\"who\">ASSISTANT");
					if (e.extra() != null && !e.extra().isEmpty()) {
						h.append(" · ").append(Text.escapeHtml(e.extra()));
					}
					h.append("</span><br>").append(MarkdownRenderer.toHtml(e.text())).append("</td></tr></table><br>");
				}
				case SYSTEM -> h.append("<p class=\"who\"><i>").append(Text.escapeHtml(e.text())).append("</i></p>");
				case META -> {
					if (expert) {
						h.append("<p class=\"who\">").append(Text.escapeHtml(e.text())).append("</p>");
					}
				}
				case ERROR -> h.append("<table width=\"100%\" cellpadding=\"6\"><tr><td bgcolor=\"").append(Theme.hex(Theme.mix(bg, Theme.bad(), 0.15)))
						.append("\"><font color=\"").append(Theme.hex(Theme.bad())).append("\"><b>Problem</b></font><br>")
						.append(Text.escapeHtml(e.text()).replace("\n", "<br>")).append("</td></tr></table><br>");
				case TOOL -> {
					h.append("<p class=\"who\">⚙ ").append(Text.escapeHtml(e.text())).append("</p>");
					if (expert && e.extra() != null && !e.extra().isEmpty()) {
						h.append("<pre>").append(Text.escapeHtml(Text.truncate(e.extra(), 1500))).append("</pre>");
					}
				}
			}
		}
		h.append("</body></html>");
		pane.setText(h.toString());
		pane.setBackground(bg);
		if (atBottom) {
			SwingUtilities.invokeLater(() -> pane.setCaretPosition(pane.getDocument().getLength()));
		}
		else {
			SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(oldValue));
		}
	}
}

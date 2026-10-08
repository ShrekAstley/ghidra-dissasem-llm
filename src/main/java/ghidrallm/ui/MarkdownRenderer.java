package ghidrallm.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ghidrallm.util.Text;

/**
 * Converts the small Markdown subset local models emit into Swing-friendly HTML 3.2/CSS1, adds
 * evidence-label badges, and turns function names/addresses into {@code goto:} links.
 */
public final class MarkdownRenderer {
	private MarkdownRenderer() {}

	public static final String GOTO = "goto:";

	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern CODE = Pattern.compile("`([^`\n]+)`");
	private static final Pattern LABEL = Pattern.compile("\\b(CONFIRMED|LIKELY|POSSIBLE|UNKNOWN|VERIFIED|INFERRED)\\b");
	private static final Pattern ADDR = Pattern.compile(
		"\\b((?:FUN|SUB|thunk_FUN|DAT|LAB|PTR)_[0-9a-fA-F]{6,16}|0x[0-9a-fA-F]{5,16})\\b");
	private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
	private static final Pattern SECTION = Pattern.compile("^([A-Z][A-Za-z /]{2,40}):?$");

	/** Renders a full message body. */
	public static String toHtml(String md) {
		StringBuilder out = new StringBuilder();
		String[] lines = md.replace("\r", "").split("\n", -1);
		boolean inCode = false, inList = false, inOl = false;
		StringBuilder code = new StringBuilder();
		for (String line : lines) {
			if (line.trim().startsWith("```")) {
				if (inCode) {
					out.append("<pre>").append(linkify(Text.escapeHtml(code.toString().stripTrailing()))).append("</pre>");
					code.setLength(0);
				}
				else {
					closeLists(out, inList, inOl);
					inList = inOl = false;
				}
				inCode = !inCode;
				continue;
			}
			if (inCode) {
				code.append(line).append('\n');
				continue;
			}
			Matcher h = HEADING.matcher(line);
			Matcher b = BULLET.matcher(line);
			Matcher n = NUMBERED.matcher(line);
			if (b.matches()) {
				if (inOl) {
					out.append("</ol>");
					inOl = false;
				}
				if (!inList) {
					out.append("<ul>");
					inList = true;
				}
				out.append("<li>").append(inline(b.group(1))).append("</li>");
				continue;
			}
			if (n.matches()) {
				if (inList) {
					out.append("</ul>");
					inList = false;
				}
				if (!inOl) {
					out.append("<ol>");
					inOl = true;
				}
				out.append("<li>").append(inline(n.group(2))).append("</li>");
				continue;
			}
			closeLists(out, inList, inOl);
			inList = inOl = false;
			if (h.matches()) {
				int lvl = Math.min(4, h.group(1).length() + 2);
				out.append("<h").append(lvl).append('>').append(inline(h.group(2))).append("</h").append(lvl).append('>');
			}
			else if (line.isBlank()) {
				out.append("<br>");
			}
			else if (SECTION.matcher(line.trim()).matches() && line.trim().length() < 40 && !line.trim().contains(".")) {
				out.append("<p><b>").append(inline(line.trim())).append("</b></p>");
			}
			else {
				out.append("<p>").append(inline(line)).append("</p>");
			}
		}
		if (inCode) {
			out.append("<pre>").append(linkify(Text.escapeHtml(code.toString().stripTrailing()))).append("</pre>");
		}
		closeLists(out, inList, inOl);
		return out.toString();
	}

	private static void closeLists(StringBuilder out, boolean ul, boolean ol) {
		if (ul) {
			out.append("</ul>");
		}
		if (ol) {
			out.append("</ol>");
		}
	}

	/** Escapes, then applies inline formatting. */
	static String inline(String raw) {
		String s = Text.escapeHtml(raw);
		s = CODE.matcher(s).replaceAll(m -> "<code>" + Matcher.quoteReplacement(linkify(m.group(1))) + "</code>");
		s = BOLD.matcher(s).replaceAll("<b>$1</b>");
		// badges must not touch text already inside a tag
		s = replaceOutsideTags(s, LABEL, m -> badge(m.group(1)));
		s = replaceOutsideTags(s, ADDR, m -> "<a href=\"" + GOTO + m.group(1) + "\">" + m.group(1) + "</a>");
		return s;
	}

	static String linkify(String escaped) {
		return replaceOutsideTags(escaped, ADDR, m -> "<a href=\"" + GOTO + m.group(1) + "\">" + m.group(1) + "</a>");
	}

	private static String badge(String label) {
		java.awt.Color c = switch (label) {
			case "CONFIRMED", "VERIFIED" -> Theme.confirmed();
			case "LIKELY" -> Theme.likely();
			case "POSSIBLE", "INFERRED" -> Theme.possible();
			default -> Theme.unknown();
		};
		return "<b><font color=\"" + Theme.hex(c) + "\">" + label + "</font></b>";
	}

	private static String replaceOutsideTags(String html, Pattern p, java.util.function.Function<Matcher, String> f) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		boolean inAnchor = false;
		while (i < html.length()) {
			int lt = html.indexOf('<', i);
			String text = lt < 0 ? html.substring(i) : html.substring(i, lt);
			if (!inAnchor) {
				Matcher m = p.matcher(text);
				StringBuilder seg = new StringBuilder();
				int last = 0;
				while (m.find()) {
					seg.append(text, last, m.start()).append(f.apply(m));
					last = m.end();
				}
				seg.append(text.substring(last));
				out.append(seg);
			}
			else {
				out.append(text);
			}
			if (lt < 0) {
				break;
			}
			int gt = html.indexOf('>', lt);
			if (gt < 0) {
				out.append(html.substring(lt));
				break;
			}
			String tag = html.substring(lt, gt + 1);
			if (tag.startsWith("<a ")) {
				inAnchor = true;
			}
			else if (tag.equals("</a>")) {
				inAnchor = false;
			}
			out.append(tag);
			i = gt + 1;
		}
		return out.toString();
	}
}

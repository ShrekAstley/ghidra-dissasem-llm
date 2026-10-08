package ghidrallm.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {
	@Test
	void escapesHtmlAndNeverInjectsTags() {
		String h = MarkdownRenderer.toHtml("<script>alert(1)</script> & <b>x</b>");
		assertFalse(h.contains("<script>"));
		assertTrue(h.contains("&lt;script&gt;"));
	}

	@Test
	void rendersStructure() {
		String h = MarkdownRenderer.toHtml("## Title\n- a **bold** `code`\n- b\n\n1. one\n2. two\n```\nraw <x>\n```");
		assertTrue(h.contains("<h4>Title</h4>"));
		assertTrue(h.contains("<ul><li>a <b>bold</b> <code>code</code></li><li>b</li></ul>"));
		assertTrue(h.contains("<ol><li>one</li><li>two</li></ol>"));
		assertTrue(h.contains("<pre>raw &lt;x&gt;</pre>"));
	}

	@Test
	void badgesAndLinks() {
		String h = MarkdownRenderer.toHtml("CONFIRMED: FUN_00401000 calls 0x00403000. UNKNOWN thing");
		assertTrue(h.contains(">CONFIRMED</font>"));
		assertTrue(h.contains("href=\"goto:FUN_00401000\""));
		assertTrue(h.contains("href=\"goto:0x00403000\""));
		assertTrue(h.contains(">UNKNOWN</font>"));
		// no nested anchors / badges inside link text
		assertFalse(h.contains("<a href=\"goto:<"));
	}

	@Test
	void prettyArgs() {
		assertEquals("function=main, limit=5", AssistantPanel.prettyArgs("{\"function\":\"main\",\"limit\":5}"));
		assertEquals("not json", AssistantPanel.prettyArgs("not json"));
	}
}

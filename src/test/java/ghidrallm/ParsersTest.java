package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import ghidrallm.agent.*;

class ParsersTest {

	@Test
	void parsesTaggedToolCall() {
		var p = ToolCallParser.parse("Checking callers.\n<tool_call>\n{\"name\": \"get_callers\", \"arguments\": {\"function\": \"FUN_1\"}}\n</tool_call>");
		assertEquals(1, p.calls().size());
		assertEquals("get_callers", p.calls().get(0).name());
		assertEquals("{\"function\":\"FUN_1\"}", p.calls().get(0).argumentsJson());
		assertEquals("Checking callers.", p.visibleText());
		assertTrue(p.errors().isEmpty());
	}

	@Test
	void parsesMultipleAndAlternateKeys() {
		var p = ToolCallParser.parse("<tool_call>{\"tool\":\"a_b\",\"parameters\":{\"x\":1}}</tool_call>" +
			"<tool_call>```json\n{\"name\":\"c\",\"arguments\":\"{\\\"y\\\":2}\"}\n```</tool_call>");
		assertEquals(2, p.calls().size());
		assertEquals("{\"x\":1}", p.calls().get(0).argumentsJson());
		assertEquals("{\"y\":2}", p.calls().get(1).argumentsJson());
		assertNotEquals(p.calls().get(0).id(), p.calls().get(1).id());
	}

	@Test
	void toleratesMissingCloseTagWhenJsonComplete() {
		var p = ToolCallParser.parse("<tool_call>{\"name\":\"get_entry_points\",\"arguments\":{}}");
		assertEquals(1, p.calls().size());
	}

	@Test
	void reportsMalformedCalls() {
		assertFalse(ToolCallParser.parse("<tool_call>{oops</tool_call>").errors().isEmpty());
		assertTrue(ToolCallParser.parse("<tool_call>{oops</tool_call>").calls().isEmpty());
		assertFalse(ToolCallParser.parse("<tool_call>{\"arguments\":{}}</tool_call>").errors().isEmpty());
		assertFalse(ToolCallParser.parse("<tool_call>{\"name\":\"Bad Name!\",\"arguments\":{}}</tool_call>").errors().isEmpty());
		assertFalse(ToolCallParser.parse("<tool_call>{\"name\":\"x\",\"arguments\":[1]}</tool_call>").errors().isEmpty());
		assertFalse(ToolCallParser.parse("<tool_call>{\"name\":\"x\",\"arguments\":\"not json\"}</tool_call>").errors().isEmpty());
	}

	@Test
	void capsCallsPerMessage() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			sb.append("<tool_call>{\"name\":\"get_entry_points\",\"arguments\":{}}</tool_call>");
		}
		var p = ToolCallParser.parse(sb.toString());
		assertEquals(ToolCallParser.MAX_CALLS_PER_MESSAGE, p.calls().size());
		assertFalse(p.errors().isEmpty());
	}

	@Test
	void plainTextHasNoCalls() {
		var p = ToolCallParser.parse("Just an answer, with {braces}.");
		assertTrue(p.calls().isEmpty());
		assertEquals("Just an answer, with {braces}.", p.visibleText());
	}

	@Test
	void stripsReasoningBlocksAndFindsLabels() {
		var r = ResponseParser.parse("<think>secret plan</think>It is LIKELY a loader. CONFIRMED: calls CreateFile.");
		assertEquals("It is LIKELY a loader. CONFIRMED: calls CreateFile.", r.answer());
		assertEquals("secret plan", r.hiddenReasoning());
		assertEquals(java.util.List.of("LIKELY", "CONFIRMED"), r.labels());
		assertEquals("answer", ResponseParser.parse("reasoning...</think>answer").answer());
		assertEquals("before", ResponseParser.parse("before<think>unfinished").answer());
	}

	@Test
	void parsesArchitectureReport() {
		String txt = "Overview text.\n```json\n{\"overview\":\"entry -> init -> loop\",\"subsystems\":[" +
			"{\"name\":\"Initialization\",\"description\":\"boot\",\"confidence\":\"LIKELY\",\"functions\":[\"a@1\"],\"children\":[{\"name\":\"Config\",\"functions\":[\"b@2\"]}]}," +
			"{\"name\":\"Networking\"}]}\n```";
		ArchitectureReport r = ArchitectureReport.parse(txt);
		assertEquals(2, r.root.children.size());
		assertEquals("Config", r.root.children.get(0).children.get(0).name);
		assertEquals("entry -> init -> loop", r.overview);
		String tree = r.toTreeText();
		assertTrue(tree.contains("├── Initialization"));
		assertTrue(tree.contains("└── Networking"));
		assertTrue(tree.contains("    └── Config") || tree.contains("│   └── Config"));
		// garbage in -> still no exception
		assertTrue(ArchitectureReport.parse("no json here").root.children.isEmpty());
		assertTrue(ArchitectureReport.parse("{broken").root.children.isEmpty());
	}
}

package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import ghidrallm.agent.*;
import ghidrallm.llm.ChatMessage;

class ContextManagerTest {
	private final ContextManager cm = new ContextManager();

	private static ContextItem item(ContextPriority p, String key, String text) {
		return new ContextItem(p, key, "Title " + key, text, "tool_" + key);
	}

	@Test
	void ordersByPriorityNotInsertion() {
		var b = cm.assemble(List.of(item(ContextPriority.XREFS, "x", "xref data"),
			item(ContextPriority.CURRENT_FUNCTION, "f", "function header"),
			item(ContextPriority.ASSEMBLY, "a", "asm data")), Set.of(), 10_000);
		int f = b.text().indexOf("function header"), a = b.text().indexOf("asm data"), x = b.text().indexOf("xref data");
		assertTrue(f >= 0 && f < a && a < x, b.text());
	}

	@Test
	void dropsLowPriorityFirstWhenTight() {
		String big = "line of assembly\n".repeat(300);
		var b = cm.assemble(List.of(item(ContextPriority.CURRENT_FUNCTION, "f", "header"),
			item(ContextPriority.ASSEMBLY, "a", big),
			item(ContextPriority.DECOMPILATION, "d", big.replace("assembly", "decomp")),
			item(ContextPriority.BROADER_PROGRAM, "z", "broad stuff ".repeat(200))), Set.of(), 700);
		assertTrue(b.text().contains("header"));
		assertTrue(b.includedKeys().contains("a"));
		assertTrue(b.estimatedTokens() <= 760, "tokens " + b.estimatedTokens());
		assertFalse(b.omittedTitles().isEmpty() && b.truncatedTitles().isEmpty());
		assertTrue(b.text().contains("[Truncated") || b.text().contains("[Omitted"));
		assertTrue(b.text().contains("tool_"), "fetch hint present");
	}

	@Test
	void truncationKeepsHeadAndTail() {
		String code = "START" + "x".repeat(20_000) + "END";
		var b = cm.assemble(List.of(item(ContextPriority.DECOMPILATION, "d", code)), Set.of(), 800);
		assertTrue(b.text().contains("START"));
		assertTrue(b.text().contains("END"));
		assertTrue(b.text().contains("elided"));
		assertEquals(1, b.truncatedTitles().size());
	}

	@Test
	void dedupesByKeyAndContent() {
		var b = cm.assemble(List.of(item(ContextPriority.ASSEMBLY, "a", "same text"),
			item(ContextPriority.ASSEMBLY, "a", "other text with same key"),
			item(ContextPriority.STRINGS, "s", "same text")), Set.of(), 5000);
		assertEquals(1, b.includedKeys().size());
	}

	@Test
	void skipsAlreadySentItemsAndSaysSo() {
		var b = cm.assemble(List.of(item(ContextPriority.ASSEMBLY, "a", "asm"), item(ContextPriority.STRINGS, "s", "strs")),
			Set.of("a"), 5000);
		assertFalse(b.text().contains("asm\n"));
		assertTrue(b.text().contains("strs"));
		assertTrue(b.text().contains("Already provided earlier"));
		assertEquals(List.of("s"), b.includedKeys());
	}

	@Test
	void ignoresEmptyItems() {
		var b = cm.assemble(List.of(item(ContextPriority.ASSEMBLY, "a", "  "), item(ContextPriority.STRINGS, "s", "")), Set.of(), 1000);
		assertTrue(b.text().isEmpty());
	}

	@Test
	void fitDropsOldestTurnsButKeepsLatestUser() {
		List<List<ChatMessage>> turns = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			turns.add(List.of(ChatMessage.user("question " + i + " " + "pad ".repeat(300)), ChatMessage.assistant("answer " + i + " " + "pad ".repeat(300))));
		}
		var out = cm.fit(ChatMessage.system("sys"), turns, 1200, 0);
		assertEquals("system", out.get(0).role());
		String all = out.toString();
		assertTrue(all.contains("question 5"), "latest kept");
		assertFalse(all.contains("question 0"), "oldest dropped");
		assertTrue(all.contains("Earlier conversation turns were dropped"));
	}

	@Test
	void fitElidesOldToolResultsBeforeDroppingTurns() {
		List<List<ChatMessage>> turns = List.of(
			List.of(ChatMessage.user("q1"), ChatMessage.tool("c", "get_callers", "BIG RESULT ".repeat(400)), ChatMessage.assistant("a1")),
			List.of(ChatMessage.user("q2")));
		var out = cm.fit(ChatMessage.system("sys"), turns, 600, 0);
		String all = out.toString();
		assertTrue(all.contains("q1"), "turn kept");
		assertTrue(all.contains("earlier tool result elided"));
		assertFalse(all.contains("BIG RESULT BIG RESULT"));
	}

	@Test
	void fitShrinksOversizedCurrentToolResultAndMergesUserRuns() {
		List<List<ChatMessage>> turns = List.of(List.of(ChatMessage.user("q"),
			ChatMessage.tool("c", "t", "R".repeat(30_000)), ChatMessage.user("nudge one"), ChatMessage.user("nudge two")));
		var out = cm.fit(ChatMessage.system("sys"), turns, 2000, 0);
		int chars = out.stream().mapToInt(m -> m.content().length()).sum();
		assertTrue(chars < 9000, "shrunk, was " + chars);
		long consecutiveUsers = 0;
		for (int i = 1; i < out.size(); i++) {
			if (out.get(i).role().equals("user") && out.get(i - 1).role().equals("user")) {
				consecutiveUsers++;
			}
		}
		assertEquals(0, consecutiveUsers);
	}

	@Test
	void conversationMemoryAndRegenerate() {
		Conversation c = new Conversation();
		var t1 = c.startTurn("q1", "FUN_1", ChatMessage.user("q1"), List.of("k1"));
		c.finishTurn(t1, "It loads assets.");
		assertTrue(c.memoryBlock().contains("FUN_1: It loads assets."));
		assertTrue(c.sentContextKeys().contains("k1"));
		c.startTurn("q2", "FUN_2", ChatMessage.user("q2"), List.of("k2"));
		c.popLastTurn();
		assertFalse(c.sentContextKeys().contains("k2"));
		assertTrue(c.sentContextKeys().contains("k1"));
		c.clear();
		assertTrue(c.isEmpty());
		assertEquals("", c.memoryBlock());
	}
}

package ghidrallm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ghidrallm.config.Settings;
import ghidrallm.config.SettingsStore;
import ghidrallm.knowledge.KnowledgeStore;
import ghidrallm.knowledge.Note;

class KnowledgeAndSettingsTest {
	@TempDir
	Path tmp;

	@Test
	void notesPersistAcrossReopenAndAreScopedPerProgram() throws Exception {
		Path db = tmp.resolve("sub/knowledge.db");
		try (KnowledgeStore k = KnowledgeStore.open(db)) {
			k.add("a.exe|1", Note.Kind.FUNCTION_SUMMARY, "f1", "401000", "loads config", Note.Source.AI, "LIKELY");
			k.add("a.exe|1", Note.Kind.USER_NOTE, "f1", "401000", "my note", Note.Source.USER, null);
			k.add("b.exe|2", Note.Kind.HYPOTHESIS, "x", null, "other program", Note.Source.AI, "POSSIBLE");
		}
		try (KnowledgeStore k = KnowledgeStore.open(db)) {
			assertEquals(2, k.list("a.exe|1", null, 10).size());
			assertEquals(1, k.list("b.exe|2", null, 10).size());
			assertEquals(1, k.search("a.exe|1", "CONFIG", 10).size());
			assertEquals(2, k.forAddress("a.exe|1", "401000").size());
			assertEquals(1, k.list("a.exe|1", Note.Kind.USER_NOTE, 10).size());
		}
	}

	@Test
	void summariesReplaceButUserNotesSurvive() throws Exception {
		try (KnowledgeStore k = KnowledgeStore.inMemory()) {
			k.add("p", Note.Kind.FUNCTION_SUMMARY, "f", "1", "old", Note.Source.AI, null);
			k.add("p", Note.Kind.FUNCTION_SUMMARY, "f", "1", "new", Note.Source.AI, null);
			List<Note> n = k.list("p", Note.Kind.FUNCTION_SUMMARY, 10);
			assertEquals(1, n.size());
			assertEquals("new", n.get(0).text());
			k.add("p", Note.Kind.USER_NOTE, "f", "1", "keep me", Note.Source.USER, null);
			k.add("p", Note.Kind.USER_NOTE, "f", "1", "and me", Note.Source.USER, null);
			assertEquals(2, k.list("p", Note.Kind.USER_NOTE, 10).size());
			k.delete(n.get(0).id());
			assertTrue(k.list("p", Note.Kind.FUNCTION_SUMMARY, 10).isEmpty());
			k.clearProgram("p");
			assertTrue(k.list("p", null, 10).isEmpty());
		}
	}

	@Test
	void sqlInjectionAttemptsAreInert() throws Exception {
		try (KnowledgeStore k = KnowledgeStore.inMemory()) {
			k.add("p", Note.Kind.HYPOTHESIS, "x'); DROP TABLE notes;--", null, "t", Note.Source.AI, null);
			assertEquals(1, k.search("p", "'); DROP", 10).size());
			assertEquals(1, k.list("p", null, 10).size());
		}
	}

	@Test
	void settingsRoundTripNormalizeAndSurviveCorruption() throws Exception {
		SettingsStore store = new SettingsStore(tmp.resolve("cfg"));
		Settings d = store.load();
		assertEquals("http://localhost:1234/v1", d.endpoint);
		assertFalse(d.allowNonLoopbackEndpoint);
		d.endpoint = "http://localhost:5555/v1///";
		d.temperature = 9;
		d.maxToolCalls = -4;
		d.toolMode = "garbage";
		store.save(d);
		Settings s = store.load();
		assertEquals("http://localhost:5555/v1", s.endpoint);
		assertEquals(2.0, s.temperature, 0);
		assertEquals(0, s.maxToolCalls);
		assertEquals("AUTO", s.toolMode);
		Files.writeString(store.getFile(), "{ this is not json");
		assertEquals("http://localhost:1234/v1", store.load().endpoint);
	}
}

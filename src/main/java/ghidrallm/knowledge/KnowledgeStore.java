package ghidrallm.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight local SQLite store for analysis knowledge (summaries, hypotheses, relationships,
 * subsystem classifications, approved names, user notes). The file never leaves the machine.
 */
public class KnowledgeStore implements AutoCloseable {

	private final Connection conn;

	private KnowledgeStore(Connection c) {
		this.conn = c;
	}

	public static KnowledgeStore open(Path file) throws SQLException {
		try {
			Files.createDirectories(file.getParent());
		}
		catch (java.io.IOException e) {
			throw new SQLException("Cannot create knowledge directory: " + e.getMessage(), e);
		}
		return create("jdbc:sqlite:" + file.toAbsolutePath());
	}

	public static KnowledgeStore inMemory() throws SQLException {
		return create("jdbc:sqlite::memory:");
	}

	private static KnowledgeStore create(String url) throws SQLException {
		Connection c;
		try {
			// Direct driver instantiation avoids DriverManager classloader issues inside Ghidra.
			c = new org.sqlite.JDBC().connect(url, new java.util.Properties());
		}
		catch (NoClassDefFoundError e) {
			throw new SQLException("SQLite driver is not available: " + e.getMessage());
		}
		if (c == null) {
			throw new SQLException("SQLite driver rejected URL " + url);
		}
		try (Statement s = c.createStatement()) {
			s.execute("""
					CREATE TABLE IF NOT EXISTS notes (
					  id INTEGER PRIMARY KEY AUTOINCREMENT,
					  program_key TEXT NOT NULL,
					  kind TEXT NOT NULL,
					  subject TEXT NOT NULL,
					  address TEXT,
					  text TEXT NOT NULL,
					  source TEXT NOT NULL,
					  confidence TEXT,
					  created_at TEXT NOT NULL)""");
			s.execute("CREATE INDEX IF NOT EXISTS idx_notes_prog ON notes(program_key, kind)");
		}
		return new KnowledgeStore(c);
	}

	/** Inserts a note; FUNCTION_SUMMARY / SUBSYSTEM / ARCHITECTURE notes replace a prior one for the same subject. */
	public synchronized long add(String programKey, Note.Kind kind, String subject, String address,
			String text, Note.Source source, String confidence) throws SQLException {
		if (kind == Note.Kind.FUNCTION_SUMMARY || kind == Note.Kind.SUBSYSTEM ||
			kind == Note.Kind.ARCHITECTURE) {
			try (PreparedStatement d = conn.prepareStatement(
				"DELETE FROM notes WHERE program_key=? AND kind=? AND subject=? AND source<>'USER'")) {
				d.setString(1, programKey);
				d.setString(2, kind.name());
				d.setString(3, subject);
				d.executeUpdate();
			}
		}
		try (PreparedStatement ps = conn.prepareStatement(
			"INSERT INTO notes(program_key,kind,subject,address,text,source,confidence,created_at) VALUES(?,?,?,?,?,?,?,?)",
			Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, programKey);
			ps.setString(2, kind.name());
			ps.setString(3, subject);
			ps.setString(4, address);
			ps.setString(5, text);
			ps.setString(6, source.name());
			ps.setString(7, confidence);
			ps.setString(8, Instant.now().toString());
			ps.executeUpdate();
			try (ResultSet rs = ps.getGeneratedKeys()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
	}

	public synchronized List<Note> search(String programKey, String query, int limit)
			throws SQLException {
		String like = "%" + (query == null ? "" : query.toLowerCase().replace("%", "")) + "%";
		return query("SELECT * FROM notes WHERE program_key=? AND (lower(subject) LIKE ? OR lower(text) LIKE ? OR lower(IFNULL(address,'')) LIKE ?) ORDER BY id DESC LIMIT ?",
			programKey, like, like, like, limit);
	}

	public synchronized List<Note> forAddress(String programKey, String address) throws SQLException {
		return query("SELECT * FROM notes WHERE program_key=? AND address=? ORDER BY id DESC LIMIT 20",
			programKey, address);
	}

	public synchronized List<Note> list(String programKey, Note.Kind kind, int limit)
			throws SQLException {
		if (kind == null) {
			return query("SELECT * FROM notes WHERE program_key=? ORDER BY id DESC LIMIT ?", programKey,
				limit);
		}
		return query("SELECT * FROM notes WHERE program_key=? AND kind=? ORDER BY id DESC LIMIT ?",
			programKey, kind.name(), limit);
	}

	public synchronized void delete(long id) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM notes WHERE id=?")) {
			ps.setLong(1, id);
			ps.executeUpdate();
		}
	}

	public synchronized void clearProgram(String programKey) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM notes WHERE program_key=?")) {
			ps.setString(1, programKey);
			ps.executeUpdate();
		}
	}

	private List<Note> query(String sql, Object... params) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				ps.setObject(i + 1, params[i]);
			}
			List<Note> out = new ArrayList<>();
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Note(rs.getLong("id"), rs.getString("program_key"),
						Note.Kind.valueOf(rs.getString("kind")), rs.getString("subject"),
						rs.getString("address"), rs.getString("text"),
						Note.Source.valueOf(rs.getString("source")), rs.getString("confidence"),
						rs.getString("created_at")));
				}
			}
			return out;
		}
	}

	@Override
	public synchronized void close() {
		try {
			conn.close();
		}
		catch (SQLException e) {
			// ignore
		}
	}
}

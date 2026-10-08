package ghidrallm.changes;

import java.util.Map;

import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;
import ghidra.util.data.DataTypeParser;
import ghidra.util.data.DataTypeParser.AllowedDataTypes;

/** Parses C-ish type strings (with common stdint aliases) against a program's data types. */
final class TypeParsing {
	private TypeParsing() {}

	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("int8_t", "char"), Map.entry("uint8_t", "uchar"), Map.entry("int16_t", "short"),
		Map.entry("uint16_t", "ushort"), Map.entry("int32_t", "int"), Map.entry("uint32_t", "uint"),
		Map.entry("int64_t", "longlong"), Map.entry("uint64_t", "ulonglong"),
		Map.entry("size_t", "ulonglong"), Map.entry("ssize_t", "longlong"),
		Map.entry("uintptr_t", "ulonglong"), Map.entry("intptr_t", "longlong"),
		Map.entry("BYTE", "byte"), Map.entry("WORD", "word"), Map.entry("DWORD", "dword"),
		Map.entry("QWORD", "qword"));

	static DataType parse(DataTypeManager dtm, String text) throws ChangeException {
		if (text == null || text.isBlank()) {
			throw new ChangeException("Empty type.");
		}
		String t = text.trim();
		for (var e : ALIASES.entrySet()) {
			t = t.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
		}
		t = t.replaceFirst("^(struct|enum|union)\\s+", "");
		try {
			DataTypeParser p = new DataTypeParser(dtm, dtm, null, AllowedDataTypes.FIXED_LENGTH);
			DataType dt = p.parse(t);
			if (dt == null) {
				throw new ChangeException("Unknown type '" + text + "'.");
			}
			return dt;
		}
		catch (ghidra.util.exception.CancelledException | ghidra.program.model.data.InvalidDataTypeException e) {
			throw new ChangeException("Unknown or unsupported type '" + text + "': " + e.getMessage());
		}
		catch (RuntimeException e) {
			throw new ChangeException("Unknown or unsupported type '" + text + "'.");
		}
	}
}

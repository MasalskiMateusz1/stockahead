package pl.regavio.stockahead.parts;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an uploaded parts CSV into header-mapped records, independent of
 * what a part is. Only the file's dialect and encoding are handled here; the
 * row rules live in {@link PartsImportFile}.
 * <p>
 * Format: the first record is a header that must name the three
 * {@link Column}s, in Polish ({@code Nazwa}, {@code Ilość} or {@code Ilosc},
 * {@code Lokalizacja}) or English ({@code Name}, {@code Quantity},
 * {@code Location}) whatever the viewer's locale, so a file made under one
 * language imports under another. Names match case-insensitively after
 * {@link PartLocation#normalize}, in any order; other columns are ignored. The delimiter is {@code ;} if the header
 * line has a {@code ;} outside quotes, otherwise {@code ,}. Fields may be
 * {@code "}-quoted, and a quoted field may hold the delimiter, {@code ""}
 * (one quote) and line breaks. CRLF and LF both end a record.
 * <p>
 * Encoding: a UTF-8 byte-order mark means UTF-8 (Excel's "CSV UTF-8").
 * Without one, the bytes are decoded as strict UTF-8, and malformed input
 * falls back to Windows-1250 (Excel's plain "CSV" on a Polish system).
 * <p>
 * Every record carries its Excel row number: the header is row 1 and each
 * following record, blank ones included, adds 1. A quoted multi-line cell is
 * one record, as Excel shows it as one row.
 */
final class PartsCsvReader {

	static final int MAX_DATA_ROWS = 5_000;

	/**
	 * A required column: the header names it is recognized by, and the bundle
	 * key of the name shown to the viewer.
	 */
	enum Column {

		NAME("partsImport.column.name", "Nazwa", "Name"),
		QUANTITY("partsImport.column.quantity", "Ilość", "Ilosc", "Quantity"),
		LOCATION("partsImport.column.location", "Lokalizacja", "Location");

		private final String labelKey;

		private final List<String> headerNames;

		Column(String labelKey, String... headerNames) {
			this.labelKey = labelKey;
			this.headerNames = List.of(headerNames);
		}

		String labelKey() {
			return labelKey;
		}

	}

	/**
	 * The {@code partsImport.error.missingColumns} argument: the columns the
	 * header lacks, rendered in the viewer's language by the controller.
	 */
	record MissingColumns(List<Column> columns) implements Serializable {

		MissingColumns {
			columns = List.copyOf(columns);
		}

	}

	private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");

	private static final int HEADER_ROW = 1;

	private PartsCsvReader() {
	}

	/**
	 * One data record's raw cells, untrimmed; a column the record is too short
	 * to reach reads as {@code ""}.
	 */
	record Record(int rowNumber, String name, String quantity, String location) {

		boolean isBlank() {
			return PartLocation.normalize(name).isEmpty() && PartLocation.normalize(quantity).isEmpty()
					&& PartLocation.normalize(location).isEmpty();
		}

	}

	/** Either a structural error (and no records) or the data records in file order. */
	record Result(ImportError error, List<Record> records) {

		static Result failure(ImportError error) {
			return new Result(error, List.of());
		}

		boolean isError() {
			return error != null;
		}

	}

	static Result read(byte[] bytes) {
		String text = decode(bytes);
		if (PartLocation.normalize(text).isEmpty()) {
			return Result.failure(ImportError.ofFile("partsImport.error.emptyFile"));
		}

		char delimiter = detectDelimiter(text);
		List<List<String>> rawRecords = new ArrayList<>();
		ImportError splitError = split(text, delimiter, rawRecords);
		if (splitError != null) {
			return Result.failure(splitError);
		}

		List<String> header = rawRecords.get(0);
		int nameIndex = columnIndex(header, Column.NAME);
		int quantityIndex = columnIndex(header, Column.QUANTITY);
		int locationIndex = columnIndex(header, Column.LOCATION);
		List<Column> missing = new ArrayList<>();
		if (nameIndex < 0) {
			missing.add(Column.NAME);
		}
		if (quantityIndex < 0) {
			missing.add(Column.QUANTITY);
		}
		if (locationIndex < 0) {
			missing.add(Column.LOCATION);
		}
		if (!missing.isEmpty()) {
			return Result.failure(ImportError.ofFile("partsImport.error.missingColumns", new MissingColumns(missing)));
		}

		List<Record> records = new ArrayList<>(rawRecords.size() - 1);
		int dataRows = 0;
		for (int i = 1; i < rawRecords.size(); i++) {
			List<String> cells = rawRecords.get(i);
			Record record = new Record(HEADER_ROW + i, cell(cells, nameIndex), cell(cells, quantityIndex),
					cell(cells, locationIndex));
			if (!record.isBlank()) {
				dataRows++;
			}
			records.add(record);
		}
		if (dataRows > MAX_DATA_ROWS) {
			return Result.failure(ImportError.ofFile("partsImport.error.tooManyRows", MAX_DATA_ROWS));
		}
		return new Result(null, List.copyOf(records));
	}

	private static String decode(byte[] bytes) {
		if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
			return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException ex) {
			return new String(bytes, WINDOWS_1250);
		}
	}

	/**
	 * Scans the header record (up to its first line break outside quotes) for
	 * a {@code ;} outside quotes.
	 */
	private static char detectDelimiter(String text) {
		boolean inQuotes = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
			}
			else if (!inQuotes && (c == '\r' || c == '\n')) {
				break;
			}
			else if (!inQuotes && c == ';') {
				return ';';
			}
		}
		return ',';
	}

	/**
	 * Splits the text into records of cells. A quote opens a quoted field
	 * only at the start of a field; elsewhere it is kept as a literal
	 * character, and text after a closing quote is appended to the field.
	 * Returns an error if the text ends inside a quoted field, naming the row
	 * where that field's record starts.
	 */
	private static ImportError split(String text, char delimiter, List<List<String>> records) {
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean inQuotes = false;
		boolean atFieldStart = true;
		boolean recordPending = false;
		int length = text.length();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < length && text.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					}
					else {
						inQuotes = false;
					}
				}
				else {
					cell.append(c);
				}
				continue;
			}
			if (c == '"' && atFieldStart) {
				inQuotes = true;
				atFieldStart = false;
				recordPending = true;
			}
			else if (c == delimiter) {
				cells.add(cell.toString());
				cell.setLength(0);
				atFieldStart = true;
				recordPending = true;
			}
			else if (c == '\r' || c == '\n') {
				if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
					i++;
				}
				cells.add(cell.toString());
				records.add(cells);
				cells = new ArrayList<>();
				cell.setLength(0);
				atFieldStart = true;
				recordPending = false;
			}
			else {
				cell.append(c);
				atFieldStart = false;
				recordPending = true;
			}
		}
		if (inQuotes) {
			return ImportError.ofRow(HEADER_ROW + records.size(), "partsImport.error.unterminatedQuote");
		}
		if (recordPending) {
			cells.add(cell.toString());
			records.add(cells);
		}
		return null;
	}

	private static int columnIndex(List<String> header, Column column) {
		for (int i = 0; i < header.size(); i++) {
			String cell = Normalizer.normalize(PartLocation.normalize(header.get(i)), Normalizer.Form.NFC);
			if (column.headerNames.stream().anyMatch(cell::equalsIgnoreCase)) {
				return i;
			}
		}
		return -1;
	}

	private static String cell(List<String> cells, int index) {
		return index < cells.size() ? cells.get(index) : "";
	}

}

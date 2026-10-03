package pl.regavio.stockahead.parts;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of an uploaded parts CSV, validated and merged per part. Every row
 * is checked and every error collected, so the manager can fix the whole file
 * at once; a file with any error is rejected as a whole by the caller.
 * <p>
 * Row rules: a row whose every column is blank is skipped. The name is
 * required and at most {@value #MAX_NAME_LENGTH} characters after
 * {@link PartLocation#normalize}. The quantity is required and an integer in
 * 0..{@value #MAX_ROW_QUANTITY}. The location is optional and at most
 * {@value #MAX_LOCATION_LENGTH} characters after {@code normalize}. A file
 * with no non-blank row is an error.
 * <p>
 * Valid rows merge per {@link #nameKey}: the first spelling of the name in
 * the file (normalized) names the line, quantities are summed as a
 * {@code long} and must stay at most {@value #MAX_LINE_QUANTITY}, and the
 * non-blank locations are collected in file order without repeats under
 * {@link PartLocation#sameLocation} (the first spelling wins). Whether a new
 * part has a location is not checked here, because that needs the catalog.
 */
final class PartsImportFile {

	static final int MAX_NAME_LENGTH = 255;

	static final int MAX_LOCATION_LENGTH = 255;

	static final int MAX_ROW_QUANTITY = 1_000_000;

	static final long MAX_LINE_QUANTITY = 1_000_000_000L;

	private final List<ImportError> errors;

	private final Map<String, ImportLine> lines;

	private PartsImportFile(List<ImportError> errors, Map<String, ImportLine> lines) {
		this.errors = errors;
		this.lines = lines;
	}

	/**
	 * One part's merged file rows: the line's name, summed quantity, distinct
	 * locations and the Excel row numbers it came from. Plain values only, so
	 * it can be kept in the HTTP session.
	 */
	record ImportLine(String name, long quantity, List<String> locations, List<Integer> rowNumbers)
			implements Serializable {

		ImportLine {
			locations = List.copyOf(locations);
			rowNumbers = List.copyOf(rowNumbers);
		}

	}

	static PartsImportFile parse(List<PartsCsvReader.Record> records) {
		List<ImportError> errors = new ArrayList<>();
		Map<String, LineBuilder> builders = new LinkedHashMap<>();
		boolean anyData = false;
		for (PartsCsvReader.Record record : records) {
			if (record.isBlank()) {
				continue;
			}
			anyData = true;
			int row = record.rowNumber();
			boolean valid = true;

			String name = PartLocation.normalize(record.name());
			if (name.isEmpty()) {
				errors.add(ImportError.ofRow(row, "partsImport.error.nameRequired"));
				valid = false;
			}
			else if (name.length() > MAX_NAME_LENGTH) {
				errors.add(ImportError.ofRow(row, "partsImport.error.nameTooLong", MAX_NAME_LENGTH));
				valid = false;
			}

			long quantity = 0;
			String rawQuantity = PartLocation.normalize(record.quantity());
			if (rawQuantity.isEmpty()) {
				errors.add(ImportError.ofRow(row, "partsImport.error.quantityRequired"));
				valid = false;
			}
			else {
				BigInteger parsed = parseInteger(rawQuantity);
				if (parsed == null) {
					errors.add(ImportError.ofRow(row, "partsImport.error.quantityNotInteger"));
					valid = false;
				}
				else if (parsed.signum() < 0) {
					errors.add(ImportError.ofRow(row, "partsImport.error.quantityNegative"));
					valid = false;
				}
				else if (parsed.compareTo(BigInteger.valueOf(MAX_ROW_QUANTITY)) > 0) {
					errors.add(ImportError.ofRow(row, "partsImport.error.quantityTooLarge", MAX_ROW_QUANTITY));
					valid = false;
				}
				else {
					quantity = parsed.longValueExact();
				}
			}

			String location = PartLocation.normalize(record.location());
			if (location.length() > MAX_LOCATION_LENGTH) {
				errors.add(ImportError.ofRow(row, "partsImport.error.locationTooLong", MAX_LOCATION_LENGTH));
				valid = false;
			}

			if (!valid) {
				continue;
			}
			LineBuilder line = builders.computeIfAbsent(nameKey(name), key -> new LineBuilder(name));
			line.add(row, quantity, location);
			if (line.quantity > MAX_LINE_QUANTITY && !line.totalErrorReported) {
				line.totalErrorReported = true;
				errors.add(ImportError.ofRow(row, "partsImport.error.totalTooLarge", line.name, MAX_LINE_QUANTITY));
			}
		}
		if (!anyData) {
			errors.add(ImportError.ofFile("partsImport.error.noDataRows"));
		}

		Map<String, ImportLine> lines = new LinkedHashMap<>();
		builders.forEach((key, builder) -> lines.put(key, builder.build()));
		return new PartsImportFile(List.copyOf(errors), Collections.unmodifiableMap(lines));
	}

	/**
	 * The merge key of a part name: {@link PartLocation#normalize}, then each
	 * code point folded the way {@link String#equalsIgnoreCase} compares it
	 * (upper-cased, then lower-cased). Two names with the same key name the
	 * same part.
	 */
	static String nameKey(String name) {
		return PartLocation.normalize(name).codePoints()
			.map(codePoint -> Character.toLowerCase(Character.toUpperCase(codePoint)))
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
	}

	/** Every error found, in file order. Empty when the file is valid. */
	List<ImportError> errors() {
		return errors;
	}

	/** The merged lines keyed by {@link #nameKey}, in first-seen order. */
	Map<String, ImportLine> lines() {
		return lines;
	}

	boolean hasErrors() {
		return !errors.isEmpty();
	}

	/** An optional sign followed by ASCII digits only; anything else is not an integer. */
	private static BigInteger parseInteger(String raw) {
		if (!raw.matches("[+-]?[0-9]+")) {
			return null;
		}
		return new BigInteger(raw);
	}

	private static final class LineBuilder {

		private final String name;

		private long quantity;

		private final List<String> locations = new ArrayList<>();

		private final List<Integer> rowNumbers = new ArrayList<>();

		private boolean totalErrorReported;

		LineBuilder(String name) {
			this.name = name;
		}

		void add(int rowNumber, long rowQuantity, String location) {
			rowNumbers.add(rowNumber);
			// At most 5 000 rows of 10^6 each: the sum can't overflow a long.
			quantity += rowQuantity;
			if (!location.isEmpty()
					&& locations.stream().noneMatch(known -> PartLocation.sameLocation(known, location))) {
				locations.add(location);
			}
		}

		ImportLine build() {
			return new ImportLine(name, quantity, locations, rowNumbers);
		}

	}

}

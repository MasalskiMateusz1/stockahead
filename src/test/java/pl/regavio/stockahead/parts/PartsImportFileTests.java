package pl.regavio.stockahead.parts;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link PartsImportFile}: every row rule, collecting all errors with
 * their Excel row numbers, the case-insensitive merge per normalized name
 * and the merged-quantity bound.
 */
class PartsImportFileTests {

	private static PartsCsvReader.Record row(int rowNumber, String name, String quantity, String location) {
		return new PartsCsvReader.Record(rowNumber, name, quantity, location);
	}

	private static PartsImportFile parse(PartsCsvReader.Record... records) {
		return PartsImportFile.parse(List.of(records));
	}

	@Test
	void blankRowsAreSkipped() {
		PartsImportFile file = parse(row(2, "", "", ""), row(3, "  ", "\t", "​"), row(4, "Dioda", "1", "A1"));

		assertThat(file.errors()).isEmpty();
		assertThat(file.lines().values())
			.containsExactly(new PartsImportFile.ImportLine("Dioda", 1, List.of("A1"), List.of(4)));
	}

	@Test
	void onlyBlankRowsMeanNoData() {
		PartsImportFile file = parse(row(2, "", "", ""), row(3, "", "", ""));

		assertThat(file.errors()).containsExactly(ImportError.ofFile("partsImport.error.noDataRows"));
		assertThat(file.lines()).isEmpty();
	}

	@Test
	void missingNameIsAnError() {
		assertThat(parse(row(2, " ", "1", "A1")).errors())
			.containsExactly(ImportError.ofRow(2, "partsImport.error.nameRequired"));
	}

	@Test
	void missingQuantityIsAnError() {
		assertThat(parse(row(2, "Dioda", "", "A1")).errors())
			.containsExactly(ImportError.ofRow(2, "partsImport.error.quantityRequired"));
	}

	@Test
	void nonIntegerQuantityIsAnError() {
		assertThat(parse(row(2, "Dioda", "1.5", "A1"), row(3, "Dioda", "abc", "A1"), row(4, "Dioda", "1 000", "A1"))
			.errors()).containsExactly(ImportError.ofRow(2, "partsImport.error.quantityNotInteger"),
					ImportError.ofRow(3, "partsImport.error.quantityNotInteger"),
					ImportError.ofRow(4, "partsImport.error.quantityNotInteger"));
	}

	@Test
	void negativeQuantityIsAnError() {
		assertThat(parse(row(2, "Dioda", "-1", "A1")).errors())
			.containsExactly(ImportError.ofRow(2, "partsImport.error.quantityNegative"));
	}

	@Test
	void quantityAboveOneMillionIsAnError() {
		assertThat(parse(row(2, "Dioda", "1000001", "A1"), row(3, "Dioda", "99999999999999999999", "A1")).errors())
			.containsExactly(ImportError.ofRow(2, "partsImport.error.quantityTooLarge", 1_000_000),
					ImportError.ofRow(3, "partsImport.error.quantityTooLarge", 1_000_000));
	}

	@Test
	void boundaryQuantitiesAreAccepted() {
		PartsImportFile file = parse(row(2, "Dioda", "0", "A1"), row(3, "Zener", " 1000000 ", "B1"));

		assertThat(file.errors()).isEmpty();
		assertThat(file.lines().values()).extracting(PartsImportFile.ImportLine::quantity)
			.containsExactly(0L, 1_000_000L);
	}

	@Test
	void nameOrLocationLongerThan255IsAnError() {
		String longText = "x".repeat(256);
		PartsImportFile file = parse(row(2, longText, "1", "A1"), row(3, "Dioda", "1", longText),
				row(4, "y".repeat(255) + " ", "1", "z".repeat(255)));

		assertThat(file.errors()).containsExactly(ImportError.ofRow(2, "partsImport.error.nameTooLong", 255),
				ImportError.ofRow(3, "partsImport.error.locationTooLong", 255));
	}

	@Test
	void everyErrorIsCollectedWithItsRowNumber() {
		PartsImportFile file = parse(row(2, "Dioda", "1", "A1"), row(3, "", "x", "A1"), row(4, "", "", ""),
				row(5, "Zener", "-3", "x".repeat(256)), row(6, "Tranzystor", "2", ""));

		assertThat(file.errors()).containsExactly(ImportError.ofRow(3, "partsImport.error.nameRequired"),
				ImportError.ofRow(3, "partsImport.error.quantityNotInteger"),
				ImportError.ofRow(5, "partsImport.error.quantityNegative"),
				ImportError.ofRow(5, "partsImport.error.locationTooLong", 255));
	}

	@Test
	void rowsNamingTheSamePartIgnoringCaseAndSpacesMerge() {
		PartsImportFile file = parse(row(2, "rezystor 10K", "5", "A1"), row(3, "Rezystor 10k ", "7", "a1"),
				row(4, "Dioda", "1", "C3"), row(5, " REZYSTOR 10K", "0", "B2"), row(6, "rezystor 10k", "2", ""));

		assertThat(file.errors()).isEmpty();
		assertThat(file.lines()).containsOnlyKeys("rezystor 10k", "dioda");
		assertThat(file.lines().get("rezystor 10k")).isEqualTo(
				new PartsImportFile.ImportLine("rezystor 10K", 14, List.of("A1", "B2"), List.of(2, 3, 5, 6)));
		assertThat(file.lines().keySet()).containsExactly("rezystor 10k", "dioda");
	}

	@Test
	void nameKeyFoldsPolishCapitalsAndNonBreakingSpaces() {
		assertThat(PartsImportFile.nameKey("ŁĄCZNIK ")).isEqualTo(PartsImportFile.nameKey("łącznik"));
	}

	@Test
	void mergedQuantityAboveOneBillionIsAnErrorReportedOnce() {
		List<PartsCsvReader.Record> records = new ArrayList<>();
		for (int i = 0; i < 1_002; i++) {
			records.add(row(i + 2, i % 2 == 0 ? "Dioda" : "DIODA", "1000000", "A1"));
		}

		PartsImportFile file = PartsImportFile.parse(records);

		// 1 000 rows reach exactly 10^9; the 1 001st (Excel row 1 002) crosses it.
		assertThat(file.errors())
			.containsExactly(ImportError.ofRow(1_002, "partsImport.error.totalTooLarge", "Dioda", 1_000_000_000L));
	}

	@Test
	void mergedQuantityOfExactlyOneBillionIsAccepted() {
		List<PartsCsvReader.Record> records = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			records.add(row(i + 2, "Dioda", "1000000", "A1"));
		}

		PartsImportFile file = PartsImportFile.parse(records);

		assertThat(file.errors()).isEmpty();
		assertThat(file.lines().get("dioda").quantity()).isEqualTo(1_000_000_000L);
	}

}

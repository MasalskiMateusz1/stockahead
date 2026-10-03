package pl.regavio.stockahead.parts;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link PartsCsvReader}: encoding detection (UTF-8 with and without
 * a BOM, the Windows-1250 fallback), delimiter detection, RFC4180 quoting
 * with Excel row numbers, header mapping and the structural errors.
 */
class PartsCsvReaderTests {

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final String POLISH = "Złącze ąęłóśżź ĄĘŁÓŚŻŹ";

	private static PartsCsvReader.Result read(String text, Charset charset) {
		return PartsCsvReader.read(text.getBytes(charset));
	}

	private static PartsCsvReader.Result read(String text) {
		return read(text, StandardCharsets.UTF_8);
	}

	private static PartsCsvReader.Record record(int rowNumber, String name, String quantity, String location) {
		return new PartsCsvReader.Record(rowNumber, name, quantity, location);
	}

	@Test
	void utf8WithBomIsDecodedAndTheBomDropped() {
		String text = "Nazwa;Ilość;Lokalizacja\r\n" + POLISH + ";5;Regał Ż1\r\n";
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		byte[] bytes = new byte[UTF8_BOM.length + body.length];
		System.arraycopy(UTF8_BOM, 0, bytes, 0, UTF8_BOM.length);
		System.arraycopy(body, 0, bytes, UTF8_BOM.length, body.length);

		PartsCsvReader.Result result = PartsCsvReader.read(bytes);

		assertThat(result.isError()).isFalse();
		assertThat(result.records()).containsExactly(record(2, POLISH, "5", "Regał Ż1"));
	}

	@Test
	void utf8WithoutBomIsDecoded() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\n" + POLISH + ";5;Regał Ż1\n");

		assertThat(result.records()).containsExactly(record(2, POLISH, "5", "Regał Ż1"));
	}

	@Test
	void windows1250FallsBackWhenTheBytesAreNotUtf8() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\r\n" + POLISH + ";5;Regał Ż1\r\n",
				Charset.forName("windows-1250"));

		assertThat(result.isError()).isFalse();
		assertThat(result.records()).containsExactly(record(2, POLISH, "5", "Regał Ż1"));
	}

	@Test
	void semicolonDelimiterKeepsCommasInsideFields() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\nKondensator 1,5nF;3;A1\n");

		assertThat(result.records()).containsExactly(record(2, "Kondensator 1,5nF", "3", "A1"));
	}

	@Test
	void commaDelimiterIsUsedWhenTheHeaderHasNoSemicolon() {
		PartsCsvReader.Result result = read("Nazwa,Ilość,Lokalizacja\nRezystor 10k,4,A1;B2\n");

		assertThat(result.records()).containsExactly(record(2, "Rezystor 10k", "4", "A1;B2"));
	}

	@Test
	void semicolonInsideAQuotedHeaderCellDoesNotPickTheDelimiter() {
		PartsCsvReader.Result result = read("\"Uwagi; dodatkowe\",Nazwa,Ilość,Lokalizacja\nx,Dioda,1,A1\n");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", "A1"));
	}

	@Test
	void quotedFieldsHoldTheDelimiterEscapedQuotesAndLineBreaks() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\r\n"
				+ "\"Złącze; typ \"\"A\"\"\";2;\"Regał\r\nA1\"\r\n"
				+ "Dioda;1;B2\r\n");

		assertThat(result.records()).containsExactly(record(2, "Złącze; typ \"A\"", "2", "Regał\r\nA1"),
				record(3, "Dioda", "1", "B2"));
	}

	@Test
	void aQuotedLineBreakDoesNotShiftTheExcelRowNumberOfALaterBadRow() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\n"
				+ "\"Wiele\nlinii\nnazwy\";2;A1\n"
				+ "Zła;abc;A1\n");

		PartsImportFile file = PartsImportFile.parse(result.records());

		assertThat(result.records()).extracting(PartsCsvReader.Record::rowNumber).containsExactly(2, 3);
		assertThat(file.errors()).containsExactly(ImportError.ofRow(3, "partsImport.error.quantityNotInteger"));
	}

	@Test
	void blankRecordsKeepTheirRowNumbers() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\n\nDioda;1;A1\n;;\nZener;2;B1");

		assertThat(result.records()).containsExactly(record(2, "", "", ""), record(3, "Dioda", "1", "A1"),
				record(4, "", "", ""), record(5, "Zener", "2", "B1"));
	}

	@Test
	void columnsMayBeReorderedWithExtraColumnsAndHeaderCaseOrSpacingIgnored() {
		PartsCsvReader.Result result = read(" LOKALIZACJA ;Producent;ilość;nazwa;Uwagi\nA1;Vishay;7;Rezystor 10k;x\n");

		assertThat(result.records()).containsExactly(record(2, "Rezystor 10k", "7", "A1"));
	}

	@Test
	void asciiQuantityHeaderIsAccepted() {
		PartsCsvReader.Result result = read("Nazwa;Ilosc;Lokalizacja\nDioda;1;A1\n");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", "A1"));
	}

	@Test
	void englishHeaderIsAccepted() {
		PartsCsvReader.Result result = read("location,QUANTITY,Name\nA1,1,Dioda\n");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", "A1"));
	}

	@Test
	void mixedPolishAndEnglishHeaderIsAccepted() {
		PartsCsvReader.Result result = read("Name;Ilość;Lokalizacja\nDioda;1;A1\n");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", "A1"));
	}

	@Test
	void shortRecordsReadMissingCellsAsBlank() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\nDioda;1\n");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", ""));
	}

	@Test
	void missingHeaderColumnsAreAllNamed() {
		PartsCsvReader.Result result = read("Nazwa;Ilość sztuk;Regał\nDioda;1;A1\n");

		assertThat(result.error()).isEqualTo(ImportError.ofFile("partsImport.error.missingColumns",
				new PartsCsvReader.MissingColumns(List.of(PartsCsvReader.Column.QUANTITY, PartsCsvReader.Column.LOCATION))));
		assertThat(result.records()).isEmpty();
	}

	@Test
	void emptyFileIsAnError() {
		assertThat(PartsCsvReader.read(new byte[0]).error()).isEqualTo(ImportError.ofFile("partsImport.error.emptyFile"));
		assertThat(PartsCsvReader.read(UTF8_BOM).error()).isEqualTo(ImportError.ofFile("partsImport.error.emptyFile"));
		assertThat(read(" \r\n\r\n").error()).isEqualTo(ImportError.ofFile("partsImport.error.emptyFile"));
	}

	@Test
	void headerOnlyFileReadsNoRecordsAndIsRejectedAsHavingNoData() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\r\n");

		assertThat(result.isError()).isFalse();
		assertThat(result.records()).isEmpty();
		assertThat(PartsImportFile.parse(result.records()).errors())
			.containsExactly(ImportError.ofFile("partsImport.error.noDataRows"));
	}

	@Test
	void fiveThousandDataRowsAreAccepted() {
		PartsCsvReader.Result result = read(fileWithDataRows(PartsCsvReader.MAX_DATA_ROWS));

		assertThat(result.isError()).isFalse();
		assertThat(result.records()).hasSize(PartsCsvReader.MAX_DATA_ROWS);
	}

	@Test
	void moreThanFiveThousandDataRowsIsAnError() {
		PartsCsvReader.Result result = read(fileWithDataRows(PartsCsvReader.MAX_DATA_ROWS + 1));

		assertThat(result.error())
			.isEqualTo(ImportError.ofFile("partsImport.error.tooManyRows", PartsCsvReader.MAX_DATA_ROWS));
	}

	@Test
	void unterminatedQuoteNamesTheRowWhereItOpened() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\nDioda;1;A1\n\"Zła;2;A1\nDioda;1;A1\n");

		assertThat(result.error()).isEqualTo(ImportError.ofRow(3, "partsImport.error.unterminatedQuote"));
		assertThat(result.records()).isEmpty();
	}

	@Test
	void lastRecordWithoutTrailingLineBreakIsRead() {
		PartsCsvReader.Result result = read("Nazwa;Ilość;Lokalizacja\nDioda;1;A1");

		assertThat(result.records()).containsExactly(record(2, "Dioda", "1", "A1"));
	}

	private static String fileWithDataRows(int count) {
		StringBuilder text = new StringBuilder("Nazwa;Ilość;Lokalizacja\r\n");
		for (int i = 0; i < count; i++) {
			text.append("Część ").append(i).append(";1;A1\r\n");
		}
		return text.toString();
	}

}

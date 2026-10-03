package pl.regavio.stockahead.parts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers {@code PartImportController}'s upload page: the manager-only role
 * gate on GET and POST, the format description, every row error of an
 * invalid file listed with its Excel row number (capped at 50 with a
 * "…i N więcej" line), the friendly messages for an empty, absent or
 * over-1-MB upload. Then the preview against the catalog: stock before and
 * after, missing locations, new and inactive parts, case- and
 * whitespace-insensitive name matching done in Java, the catalog-dependent
 * errors (ambiguous name, new part without a location), the pending import
 * kept in the session and the manager-only cancel. Nothing in this phase
 * writes, so the catalog is asserted unchanged. Against a real Postgres via
 * Testcontainers, deliberately not {@code @Transactional}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PartImportIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "import-technician@example.com";

	private static final String MANAGER_EMAIL = "import-manager@example.com";

	private static final String HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

	private static final Pattern TABLE_ROW = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM order_lines");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
	}

	private void seedAccount(String email, Role role) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode("correct-password"));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private static MockMultipartFile csv(String text) {
		return csv(text.getBytes(StandardCharsets.UTF_8));
	}

	private static MockMultipartFile csv(byte[] bytes) {
		return new MockMultipartFile("file", "czesci.csv", "text/csv", bytes);
	}

	private String upload(MockHttpSession session, MockMultipartFile file) throws Exception {
		return mockMvc.perform(multipart("/parts/import").file(file).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("parts-import"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private String preview(MockHttpSession session, MockMultipartFile file) throws Exception {
		return mockMvc.perform(multipart("/parts/import").file(file).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("parts-import-preview"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private int partCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM parts", Integer.class);
	}

	private int locationCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM part_locations", Integer.class);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private Long seedPart(String name, int quantity, boolean active, String... locations) {
		return transactionTemplate.execute(status -> {
			Long id = jdbcTemplate.queryForObject(
					"INSERT INTO parts (name, quantity, active) VALUES (?, ?, ?) RETURNING id", Long.class, name,
					quantity, active);
			for (String location : locations) {
				jdbcTemplate.update("INSERT INTO part_locations (part_id, location) VALUES (?, ?)", id, location);
			}
			return id;
		});
	}

	private static PendingImport pendingOf(MockHttpSession session) {
		return (PendingImport) session.getAttribute(PendingImport.SESSION_ATTRIBUTE);
	}

	/**
	 * The text of the preview table row whose cells contain {@code name}, tags
	 * stripped and whitespace (non-breaking spaces included) collapsed, e.g. {@code "Rezystor 10k 6 10 16 B2"}.
	 */
	private static String rowOf(String html, String name) {
		Matcher matcher = TABLE_ROW.matcher(html);
		while (matcher.find()) {
			String text = matcher.group(1).replaceAll("<[^>]+>", " ").replaceAll("[\\s\\u00A0]+", " ").trim();
			if (text.contains(name)) {
				return text;
			}
		}
		throw new AssertionError("no preview row for " + name + " in:\n" + html);
	}

	// ---- role gate ------------------------------------------------------

	@Test
	void managerSeesTheUploadFormAndTheFormatDescription() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts/import").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("parts-import"))
			.andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
			.andExpect(content().string(containsString("name=\"file\"")))
			.andExpect(content().string(containsString("name=\"_csrf\"")))
			.andExpect(content().string(containsString("<strong>Lokalizacja</strong>")))
			.andExpect(content().string(containsString("CSV UTF-8")))
			.andExpect(content().string(containsString("td, th")));
	}

	@Test
	void technicianCannotOpenTheUploadPage() throws Exception {
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts/import").session(session)).andExpect(status().isForbidden());
	}

	@Test
	void technicianCannotUpload() throws Exception {
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(multipart("/parts/import").file(csv(HEADER + "Dioda;1;A1\r\n")).session(session).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void anonymousUserIsRedirectedToLogin() throws Exception {
		mockMvc.perform(get("/parts/import")).andExpect(status().is3xxRedirection());
	}

	// ---- file errors ----------------------------------------------------

	@Test
	void invalidFileListsEveryRowErrorWithItsExcelRowNumber() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		int partsBefore = partCount();

		String html = upload(session, csv(HEADER
				+ "Dioda;1;A1\r\n"
				+ "\"Wiele\r\nlinii\";2;A1\r\n"
				+ ";3;A1\r\n"
				+ "Zener;abc;B1\r\n"
				+ "\r\n"
				+ "Tranzystor;-1;C1\r\n"));

		assertThat(html).contains("Wiersz 4: nazwa jest wymagana.")
			.contains("Wiersz 5: ilość musi być liczbą całkowitą.")
			.contains("Wiersz 7: ilość nie może być ujemna.")
			.doesNotContain("Plik poprawny");
		assertThat(partCount()).isEqualTo(partsBefore);
	}

	@Test
	void moreThanFiftyErrorsShowFiftyAndTheRemainderCount() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		StringBuilder text = new StringBuilder(HEADER);
		for (int i = 0; i < 53; i++) {
			text.append("Dioda;x;A1\r\n");
		}

		String html = upload(session, csv(text.toString()));

		assertThat(html).contains("Wiersz 51: ilość musi być liczbą całkowitą.")
			.doesNotContain("Wiersz 52: ")
			.contains("…i 3 więcej.");
	}

	@Test
	void missingHeaderColumnIsReported() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv("Nazwa;Ilość\r\nDioda;1\r\n"));

		assertThat(html).contains("Brak wymaganych kolumn w nagłówku: Lokalizacja.");
	}

	@Test
	void missingColumnsAreNamedInTheViewersLanguage() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv("Name;Qty;Shelf\r\nDioda;1;A1\r\n"));

		assertThat(html).contains("Brak wymaganych kolumn w nagłówku: Ilość, Lokalizacja.");
	}

	@Test
	void englishHeaderIsAcceptedForPolishViewer() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv("Name;Quantity;Location\r\nDioda;1;A1\r\n"));

		assertThat(rowOf(html, "Dioda")).isEqualTo("Dioda NOWA 0 1 1 A1");
	}

	@Test
	void headerOnlyFileIsReportedAsHavingNoData() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv(HEADER));

		assertThat(html).contains("Plik nie zawiera żadnych wierszy z danymi.");
	}

	@Test
	void emptyUploadShowsTheFriendlyMessage() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv(new byte[0]));

		assertThat(html).contains("Wybierz plik CSV do zaimportowania.");
	}

	@Test
	void absentFilePartShowsTheFriendlyMessage() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = mockMvc.perform(multipart("/parts/import").session(session).with(csrf()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(html).contains("Wybierz plik CSV do zaimportowania.");
	}

	@Test
	void fileOverOneMegabyteShowsTheFriendlyMessage() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		byte[] bytes = new byte[(int) PartImportController.MAX_FILE_BYTES + 1];
		Arrays.fill(bytes, (byte) 'a');

		String html = upload(session, csv(bytes));

		assertThat(html).contains("Plik jest za duży. Maksymalny rozmiar to 1 MB.");
	}

	// ---- preview --------------------------------------------------------

	@Test
	void existingPartShowsStockBeforeAndAfterAndOnlyTheMissingLocations() throws Exception {
		Long resistorId = seedPart("Rezystor 10k", 6, true, "Regał A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		int locationsBefore = locationCount();

		String html = preview(session, csv(HEADER
				+ "Rezystor 10k;4;regał a1\r\n"
				+ "Rezystor 10k;6;B2\r\n"));

		assertThat(rowOf(html, "Rezystor 10k")).isEqualTo("Rezystor 10k 6 10 16 B2");
		assertThat(html).contains("Nowe części: <strong>0</strong>")
			.contains("aktualizowane części: <strong>1</strong>")
			.contains("dodawane sztuki: <strong>10</strong>")
			.contains("td, th")
			.doesNotContain("Błędy w pliku");
		assertThat(stockOf(resistorId)).isEqualTo(6);
		assertThat(locationCount()).isEqualTo(locationsBefore);
	}

	@Test
	void missingLocationIsListedWhenThePartLacksIt() throws Exception {
		seedPart("Rezystor 10k", 6, true, "B2");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER + "Rezystor 10k;10;A1\r\n"));

		assertThat(rowOf(html, "Rezystor 10k")).isEqualTo("Rezystor 10k 6 10 16 A1");
	}

	@Test
	void inactivePartIsLabelled() throws Exception {
		seedPart("Stary układ", 3, false, "Z9");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER + "Stary układ;2;\r\n"));

		assertThat(rowOf(html, "Stary układ")).isEqualTo("Stary układ (nieaktywna) 3 2 5");
	}

	@Test
	void newPartIsMarkedAndStartsFromZero() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		int partsBefore = partCount();

		String html = preview(session, csv(HEADER
				+ "Dioda;5;A1\r\n"
				+ "dioda;3;B2\r\n"));

		assertThat(rowOf(html, "Dioda")).isEqualTo("Dioda NOWA 0 8 8 A1, B2");
		assertThat(html).contains("Nowe części: <strong>1</strong>")
			.contains("aktualizowane części: <strong>0</strong>")
			.contains("dodawane sztuki: <strong>8</strong>");
		assertThat(partCount()).isEqualTo(partsBefore);
	}

	@Test
	void fileNameResolvesToTheCatalogNameIgnoringCaseAndWhitespace() throws Exception {
		seedPart("Rezystor 10k", 6, true, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER
				+ "rezystor 10K;5;A1\r\n"
				+ "\u00A0Rezystor 10k ;7;a1\r\n"));

		assertThat(rowOf(html, "Rezystor 10k")).isEqualTo("Rezystor 10k 6 12 18");
		assertThat(html).doesNotContain("NOWA").doesNotContain("rezystor 10K");
	}

	@Test
	void polishCapitalLettersMatchTheLowerCaseCatalogName() throws Exception {
		seedPart("łącznik", 2, true, "C3");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER + "ŁĄCZNIK;1;\r\n"));

		assertThat(rowOf(html, "łącznik")).isEqualTo("łącznik 2 1 3");
		assertThat(html).doesNotContain("NOWA");
	}

	@Test
	void catalogNameEndingInANonBreakingSpaceStillMatches() throws Exception {
		seedPart("Bezpiecznik\u00A0", 4, true, "D4");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER + "bezpiecznik;1;\r\n"));

		assertThat(rowOf(html, "Bezpiecznik")).isEqualTo("Bezpiecznik 4 1 5");
		assertThat(html).doesNotContain("NOWA");
	}

	@Test
	void previewKeepsThePendingImportInTheSession() throws Exception {
		Long resistorId = seedPart("Rezystor 10k", 6, true, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = preview(session, csv(HEADER
				+ "Rezystor 10k;10;A1\r\n"
				+ "Dioda;1;B2\r\n"));

		PendingImport pending = pendingOf(session);
		assertThat(pending).isNotNull();
		assertThat(pending.lines()).extracting(PartsImportFile.ImportLine::name)
			.containsExactly("Rezystor 10k", "Dioda");
		assertThat(pending.snapshots()).containsExactly(new PendingImport.Snapshot(resistorId, 6),
				new PendingImport.Snapshot(null, 0));
		assertThat(html).contains("action=\"/parts/import/confirm\"")
			.contains("name=\"token\" value=\"" + pending.token() + "\"")
			.contains("action=\"/parts/import/cancel\"")
			.contains("Zatwierdź import")
			.contains("Anuluj");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(pending);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThat(in.readObject()).isEqualTo(pending);
		}
	}

	@Test
	void eachUploadGetsAFreshToken() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		preview(session, csv(HEADER + "Dioda;1;A1\r\n"));
		String firstToken = pendingOf(session).token();
		preview(session, csv(HEADER + "Dioda;2;A1\r\n"));

		assertThat(pendingOf(session).token()).isNotEqualTo(firstToken);
		assertThat(pendingOf(session).lines()).singleElement()
			.extracting(PartsImportFile.ImportLine::quantity)
			.isEqualTo(2L);
	}

	// ---- catalog-dependent errors ---------------------------------------

	@Test
	void newPartWithoutALocationRejectsTheFileListingItsRow() throws Exception {
		seedPart("Rezystor 10k", 6, true, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		int partsBefore = partCount();

		String html = upload(session, csv(HEADER
				+ "Rezystor 10k;1;\r\n"
				+ "Dioda;1;\r\n"
				+ "dioda;2; \r\n"));

		assertThat(html).contains("Wiersz 3: nowa część „Dioda” musi mieć co najmniej jedną lokalizację.")
			.doesNotContain("Wiersz 2:")
			.doesNotContain("Zatwierdź import");
		assertThat(pendingOf(session)).isNull();
		assertThat(partCount()).isEqualTo(partsBefore);
	}

	@Test
	void nameMatchingTwoCatalogPartsIsAmbiguous() throws Exception {
		seedPart("Kondensator", 1, true, "A1");
		seedPart("kondensator", 2, true, "B2");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv(HEADER + "Dioda;1;C3\r\nKONDENSATOR;5;A1\r\n"));

		assertThat(html).contains("Wiersz 3: nazwa „KONDENSATOR” pasuje do kilku części w katalogu "
				+ "(Kondensator, kondensator).");
		assertThat(pendingOf(session)).isNull();
	}

	@Test
	void stockOverflowingIntRejectsTheFile() throws Exception {
		Long resistorId = seedPart("Rezystor 10k", Integer.MAX_VALUE - 5, true, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv(HEADER + "Rezystor 10k;6;A1\r\n"));

		assertThat(html).contains("Wiersz 2: stan części „Rezystor 10k” po imporcie przekroczyłby 2147483647.");
		assertThat(pendingOf(session)).isNull();
		assertThat(stockOf(resistorId)).isEqualTo(Integer.MAX_VALUE - 5);
	}

	@Test
	void catalogErrorsAreListedWithFileErrorsInRowOrder() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = upload(session, csv(HEADER
				+ "Dioda;1;\r\n"
				+ "Zener;x;B1\r\n"));

		assertThat(html.indexOf("Wiersz 2: nowa część „Dioda”")).isPositive()
			.isLessThan(html.indexOf("Wiersz 3: ilość musi być liczbą całkowitą."));
	}

	@Test
	void anInvalidUploadDropsTheEarlierPendingImport() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		preview(session, csv(HEADER + "Dioda;1;A1\r\n"));
		assertThat(pendingOf(session)).isNotNull();

		upload(session, csv(HEADER + "Dioda;x;A1\r\n"));

		assertThat(pendingOf(session)).isNull();
	}

	// ---- cancel ---------------------------------------------------------

	@Test
	void cancelClearsThePendingImportAndRedirectsToTheCatalog() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		preview(session, csv(HEADER + "Dioda;1;A1\r\n"));
		int partsBefore = partCount();

		mockMvc.perform(post("/parts/import/cancel").session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"));

		assertThat(pendingOf(session)).isNull();
		assertThat(partCount()).isEqualTo(partsBefore);
	}

	@Test
	void technicianCannotCancel() throws Exception {
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(post("/parts/import/cancel").session(session).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void uploadPageWithoutErrorsHasNoErrorTable() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts/import").session(session))
			.andExpect(content().string(not(containsString("Błędy w pliku"))));
	}

}

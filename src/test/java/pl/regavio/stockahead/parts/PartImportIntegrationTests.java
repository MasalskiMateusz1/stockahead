package pl.regavio.stockahead.parts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers {@code PartImportController}'s upload page: the manager-only role
 * gate on GET and POST, the format description, every row error of an
 * invalid file listed with its Excel row number (capped at 50 with a
 * "…i N więcej" line), the friendly messages for an empty, absent or
 * over-1-MB upload, and the placeholder for a valid file. Nothing in this
 * phase writes, so the catalog is asserted unchanged. Against a real
 * Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PartImportIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "import-technician@example.com";

	private static final String MANAGER_EMAIL = "import-manager@example.com";

	private static final String HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

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

	private int partCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM parts", Integer.class);
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

		String html = upload(session, csv("Name;Quantity;Location\r\nDioda;1;A1\r\n"));

		assertThat(html).contains("Plik poprawny: 1 pozycji.").doesNotContain("Błędy w pliku");
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

	// ---- valid file -----------------------------------------------------

	@Test
	void validFileShowsThePlaceholderWithTheMergedLineCountAndWritesNothing() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		int partsBefore = partCount();

		String html = upload(session, csv(HEADER
				+ "rezystor 10K;5;A1\r\n"
				+ "Rezystor 10k ;7;a1\r\n"
				+ "Dioda;1;B2\r\n"));

		assertThat(html).contains("Plik poprawny: 2 pozycji.").doesNotContain("Błędy w pliku");
		assertThat(partCount()).isEqualTo(partsBefore);
	}

	@Test
	void uploadPageWithoutErrorsHasNoErrorTable() throws Exception {
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts/import").session(session))
			.andExpect(content().string(not(containsString("Błędy w pliku"))));
	}

}

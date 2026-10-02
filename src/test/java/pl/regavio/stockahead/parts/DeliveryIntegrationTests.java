package pl.regavio.stockahead.parts;

import java.time.Instant;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers {@code DeliveryController}'s receipt form (Phase 1): the 5-row GET
 * for both roles with inactive parts labelled in the dropdown, the
 * {@code addRows} round-trip keeping every typed value, each row-validation
 * error re-rendering with its Polish message and the raw inputs kept, the
 * anonymous redirect to login, and that no rejected POST touches
 * {@code parts.quantity} or {@code part_locations}. Against a real Postgres
 * via Testcontainers, deliberately not {@code @Transactional}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "delivery-technician@example.com";

	private static final String MANAGER_EMAIL = "delivery-manager@example.com";

	private static final Pattern PART_SELECT = Pattern.compile("<select[^>]*name=\"partId\\d+\"");

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

	private Long resistorId;

	private Long capacitorId;

	private Long inactiveId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		resistorId = seedPart("Rezystor 10k", 40, true, "Regał A1");
		capacitorId = seedPart("Kondensator 100nF", 7, true, "Regał B2");
		inactiveId = seedPart("Stary układ", 3, false, "Regał Z9");
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

	// ---- fixtures -----------------------------------------------------

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

	private MockHttpSession technicianSession() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		return loginAs(TECHNICIAN_EMAIL);
	}

	private MockHttpSession managerSession() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		return loginAs(MANAGER_EMAIL);
	}

	private Long seedPart(String name, int quantity, boolean active, String location) {
		return transactionTemplate.execute(status -> {
			Long id = jdbcTemplate.queryForObject(
					"INSERT INTO parts (name, quantity, active) VALUES (?, ?, ?) RETURNING id", Long.class, name,
					quantity, active);
			jdbcTemplate.update("INSERT INTO part_locations (part_id, location) VALUES (?, ?)", id, location);
			return id;
		});
	}

	private String snapshot() {
		List<String> parts = jdbcTemplate.queryForList(
				"SELECT id || ':' || quantity FROM parts ORDER BY id", String.class);
		List<String> locations = jdbcTemplate.queryForList(
				"SELECT part_id || ':' || location FROM part_locations ORDER BY part_id, location", String.class);
		return parts + " / " + locations;
	}

	private MockHttpServletRequestBuilder receipt(MockHttpSession session, String... nameValuePairs) {
		MockHttpServletRequestBuilder request = post("/deliveries").session(session).with(csrf());
		for (int i = 0; i < nameValuePairs.length; i += 2) {
			request.param(nameValuePairs[i], nameValuePairs[i + 1]);
		}
		return request;
	}

	private String body(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name("deliveries-new"))
			.andReturn().getResponse().getContentAsString();
	}

	private static int rowCount(String html) {
		Matcher matcher = PART_SELECT.matcher(html);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static String selectedOption(Long partId) {
		return "<option value=\"" + partId + "\" selected=\"selected\"";
	}

	private static String inputValue(String name, String value) {
		return "name=\"" + name + "\" value=\"" + value + "\"";
	}

	/**
	 * Posts a rejected receipt and asserts the error message, that the given
	 * raw values survive the re-render, and that the database is unchanged.
	 */
	private String assertRejected(MockHttpSession session, String expectedError, String... nameValuePairs)
			throws Exception {
		String before = snapshot();
		String html = body(receipt(session, nameValuePairs));
		assertThat(html).contains(expectedError);
		assertThat(snapshot()).isEqualTo(before);
		return html;
	}

	// ---- GET ----------------------------------------------------------

	@Test
	void technicianGetRendersFiveBlankRowsWithInactivePartLabelled() throws Exception {
		MockHttpSession session = technicianSession();

		String html = mockMvc.perform(get("/deliveries/new").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("deliveries-new"))
			.andReturn().getResponse().getContentAsString();

		assertThat(rowCount(html)).isEqualTo(5);
		assertThat(html).contains("name=\"rows\" value=\"5\"");
		assertThat(html).contains(">Rezystor 10k<");
		assertThat(html).contains(">Kondensator 100nF<");
		assertThat(html).contains(">Stary układ (nieaktywna)<");
		assertThat(html).contains("<option value=\"" + inactiveId + "\"");
		assertThat(html).doesNotContain("selected=\"selected\"");
		assertThat(html).contains("td, th");
		assertThat(html).contains("list=\"known-locations\"");
		assertThat(html).contains("<datalist id=\"known-locations\">");
		assertThat(html).contains("<option value=\"Regał A1\">", "<option value=\"Regał B2\">",
				"<option value=\"Regał Z9\">");
	}

	@Test
	void managerGetRendersFiveBlankRowsWithInactivePartLabelled() throws Exception {
		MockHttpSession session = managerSession();

		String html = mockMvc.perform(get("/deliveries/new").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("deliveries-new"))
			.andReturn().getResponse().getContentAsString();

		assertThat(rowCount(html)).isEqualTo(5);
		assertThat(html).contains(">Stary układ (nieaktywna)<");
	}

	// ---- addRows ------------------------------------------------------

	@Test
	void addRowsRendersTenRowsKeepingTypedValuesWithoutValidating() throws Exception {
		MockHttpSession session = technicianSession();
		String before = snapshot();

		String html = body(receipt(session,
				"rows", "5",
				"action", "addRows",
				"partId0", resistorId.toString(), "quantity0", "12", "location0", "Regał C3",
				"partId2", inactiveId.toString(), "quantity2", "abc", "location2", "",
				"partId4", "", "quantity4", "", "location4", "tylko lokalizacja"));

		assertThat(rowCount(html)).isEqualTo(10);
		assertThat(html).contains("name=\"rows\" value=\"10\"");
		assertThat(html).contains(selectedOption(resistorId));
		assertThat(html).contains(selectedOption(inactiveId));
		assertThat(html).contains(inputValue("quantity0", "12"));
		assertThat(html).contains(inputValue("location0", "Regał C3"));
		assertThat(html).contains(inputValue("quantity2", "abc"));
		assertThat(html).contains(inputValue("location4", "tylko lokalizacja"));
		assertThat(html).contains(inputValue("quantity9", ""));
		assertThat(html).doesNotContain("Wiersz ");
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	void addRowsTwiceKeepsValuesAcrossBothRoundTrips() throws Exception {
		MockHttpSession session = technicianSession();

		String html = body(receipt(session,
				"rows", "10",
				"action", "addRows",
				"partId7", capacitorId.toString(), "quantity7", "3", "location7", "Regał B2"));

		assertThat(rowCount(html)).isEqualTo(15);
		assertThat(html).contains(selectedOption(capacitorId));
		assertThat(html).contains(inputValue("quantity7", "3"));
		assertThat(html).contains(inputValue("location7", "Regał B2"));
	}

	@Test
	void rowCountFallsBackToDefaultWhenMissingOrInvalidAndIsClamped() throws Exception {
		MockHttpSession session = technicianSession();

		assertThat(rowCount(body(receipt(session, "action", "addRows")))).isEqualTo(10);
		assertThat(rowCount(body(receipt(session, "rows", "xyz", "action", "addRows")))).isEqualTo(10);
		assertThat(rowCount(body(receipt(session, "rows", "-3", "action", "addRows")))).isEqualTo(10);
		assertThat(rowCount(body(receipt(session, "rows", "100", "action", "addRows")))).isEqualTo(100);
		assertThat(rowCount(body(receipt(session, "rows", "5000", "action", "addRows")))).isEqualTo(100);
	}

	// ---- validation errors --------------------------------------------

	@Test
	void allBlankReceiptIsRejectedAsEmpty() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Dodaj co najmniej jedną pozycję dostawy.",
				"rows", "5",
				"partId0", "", "quantity0", "  ", "location0", "");

		assertThat(rowCount(html)).isEqualTo(5);
	}

	@Test
	void rowWithPartButNoQuantityIsIncomplete() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 2: wybierz część i podaj ilość.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "10", "location0", "Regał A1",
				"partId1", capacitorId.toString(), "quantity1", "", "location1", "Regał N1");

		assertThat(html).contains(selectedOption(resistorId));
		assertThat(html).contains(selectedOption(capacitorId));
		assertThat(html).contains(inputValue("quantity0", "10"));
		assertThat(html).contains(inputValue("location1", "Regał N1"));
	}

	@Test
	void rowWithOnlyLocationIsIncomplete() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 3: wybierz część i podaj ilość.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "4",
				"partId2", "", "quantity2", "", "location2", "Regał Q7");

		assertThat(html).contains(inputValue("location2", "Regał Q7"));
		assertThat(html).contains(inputValue("quantity0", "4"));
	}

	@Test
	void rowWithQuantityButNoPartIsIncomplete() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 1: wybierz część i podaj ilość.",
				"rows", "5",
				"partId0", "", "quantity0", "8");

		assertThat(html).contains(inputValue("quantity0", "8"));
	}

	@Test
	void nonNumericPartIdIsRejected() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 1: wybierz część z listy.",
				"rows", "5",
				"partId0", "not-a-part", "quantity0", "8", "location0", "Regał A1");

		assertThat(html).contains(inputValue("quantity0", "8"));
		assertThat(html).contains(inputValue("location0", "Regał A1"));
	}

	@Test
	void nonIntegerQuantityIsRejected() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 2: ilość musi być liczbą całkowitą.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "5",
				"partId1", capacitorId.toString(), "quantity1", "2.5", "location1", "Regał B2");

		assertThat(html).contains(selectedOption(capacitorId));
		assertThat(html).contains(inputValue("quantity1", "2.5"));
		assertThat(html).contains(inputValue("location1", "Regał B2"));
	}

	@Test
	void zeroAndNegativeQuantitiesAreRejected() throws Exception {
		MockHttpSession session = technicianSession();

		String zero = assertRejected(session, "Wiersz 1: ilość musi wynosić co najmniej 1.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "0");
		assertThat(zero).contains(inputValue("quantity0", "0"));

		String negative = assertRejected(session, "Wiersz 1: ilość musi wynosić co najmniej 1.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "-5");
		assertThat(negative).contains(inputValue("quantity0", "-5"));
	}

	@Test
	void quantityAboveUpperBoundIsRejected() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 1: ilość nie może przekraczać 1000000.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "1000001");
		assertThat(html).contains(inputValue("quantity0", "1000001"));

		// Beyond int and long range: still the upper-bound error, not "not an integer".
		assertRejected(session, "Wiersz 1: ilość nie może przekraczać 1000000.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "99999999999999999999999");
	}

	@Test
	void locationLongerThan255CharactersIsRejected() throws Exception {
		MockHttpSession session = technicianSession();
		String longLocation = "L".repeat(256);

		String html = assertRejected(session, "Wiersz 1: lokalizacja może mieć maksymalnie 255 znaków.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "3", "location0", longLocation);

		assertThat(html).contains(inputValue("location0", longLocation));
		assertThat(html).contains(inputValue("quantity0", "3"));
	}

	@Test
	void locationOf255CharactersAfterTrimmingIsAccepted() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(receipt(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "3", "location0", "  " + "L".repeat(255) + "  "))
			.andExpect(status().is3xxRedirection());
	}

	@Test
	void firstErrorWinsAndLaterRowsAreKept() throws Exception {
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Wiersz 2: ilość musi wynosić co najmniej 1.",
				"rows", "5",
				"partId1", resistorId.toString(), "quantity1", "0",
				"partId3", capacitorId.toString(), "quantity3", "abc", "location3", "Regał B2");

		assertThat(html).doesNotContain("Wiersz 4");
		assertThat(html).contains(inputValue("quantity3", "abc"));
		assertThat(rowCount(html)).isEqualTo(5);
	}

	@Test
	void rowsBeyondTheRowCountAreIgnored() throws Exception {
		MockHttpSession session = technicianSession();

		assertRejected(session, "Dodaj co najmniej jedną pozycję dostawy.",
				"rows", "5",
				"partId5", resistorId.toString(), "quantity5", "10");
	}

	@Test
	void validReceiptWritesNothingInThisPhase() throws Exception {
		MockHttpSession session = managerSession();
		String before = snapshot();

		mockMvc.perform(receipt(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "50", "location0", "Regał A2",
				"partId1", resistorId.toString(), "quantity1", "30",
				"partId2", inactiveId.toString(), "quantity2", "1"))
			.andExpect(status().is3xxRedirection());

		assertThat(snapshot()).isEqualTo(before);
	}

	// ---- access -------------------------------------------------------

	@Test
	void anonymousGetRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/deliveries/new"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
	}

	@Test
	void anonymousPostRedirectsToLoginAndWritesNothing() throws Exception {
		String before = snapshot();

		mockMvc.perform(post("/deliveries").with(csrf())
			.param("rows", "5")
			.param("partId0", resistorId.toString())
			.param("quantity0", "10"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));

		assertThat(snapshot()).isEqualTo(before);
	}

}

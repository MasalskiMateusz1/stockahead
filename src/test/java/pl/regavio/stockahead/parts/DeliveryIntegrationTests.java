package pl.regavio.stockahead.parts;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers {@code DeliveryController}'s receipt form: the 5-row GET for both
 * roles with inactive parts labelled in the dropdown, the {@code addRows}
 * round-trip keeping every typed value, each row-validation error
 * re-rendering with its Polish message and the raw inputs kept, the anonymous
 * redirect to login, and that no rejected POST touches {@code parts.quantity}
 * or {@code part_locations}. Then the write path: stock raised and missing
 * locations added atomically, reservations recomputed in allocation order
 * (taken orders topped up, completion-reported ones left alone), overflow and
 * unknown parts rejecting the whole receipt, and the redirect to
 * {@code /parts} with the summary. Against a real Postgres via
 * Testcontainers, deliberately not {@code @Transactional}.
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
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_delivery_location_conflict_trigger ON part_locations");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_delivery_location_conflict()");
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

	private Long seedProject(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, name));
	}

	private Long seedOrder(Long projectId, Priority priority, LocalDate requiredDate, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, projectId, priority.name(), requiredDate, Timestamp.from(createdAt)));
	}

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity));
	}

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	private void reportCompletion(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection());
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private List<String> locationsOf(Long partId) {
		return jdbcTemplate.queryForList("SELECT location FROM part_locations WHERE part_id = ? ORDER BY location",
				String.class, partId);
	}

	/** Posts a receipt that must succeed (redirect to {@code /parts}). */
	private MvcResult receive(MockHttpSession session, String... nameValuePairs) throws Exception {
		return mockMvc.perform(receipt(session, nameValuePairs))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"))
			.andReturn();
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

	// ---- write path ---------------------------------------------------

	/**
	 * US-01: stock 6, an order needs 10. A delivery of 4 fully reserves the
	 * order and the part leaves the shopping list.
	 */
	@Test
	void deliveryCoveringAShortageFullyReservesTheOrderAndClearsPurchasing() throws Exception {
		Long partId = seedPart("Mikrokontroler", 6, true, "Regał M1");
		Long orderId = seedOrder(seedProject("Sterownik"), Priority.NORMAL, LocalDate.now().plusDays(7),
				Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 10, 6);
		MockHttpSession manager = managerSession();
		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Mikrokontroler")));
		MockHttpSession technician = technicianSession();

		receive(technician, "rows", "5", "partId0", partId.toString(), "quantity0", "4");

		assertThat(stockOf(partId)).isEqualTo(10);
		assertThat(reservedOf(lineId)).isEqualTo(10);
		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Mikrokontroler"))));
	}

	@Test
	void partialDeliveryFillsTheHighPriorityOrderFirst() throws Exception {
		Long partId = seedPart("Dioda LED", 0, true, "Regał D1");
		Long projectId = seedProject("Panel");
		// The LOW order is older and due earlier: priority still wins.
		Long lowOrderId = seedOrder(projectId, Priority.LOW, LocalDate.now().plusDays(2),
				Instant.now().minusSeconds(3600));
		Long highOrderId = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(10), Instant.now());
		Long lowLineId = seedOrderLine(lowOrderId, partId, 10, 0);
		Long highLineId = seedOrderLine(highOrderId, partId, 10, 0);
		MockHttpSession session = technicianSession();

		receive(session, "rows", "5", "partId0", partId.toString(), "quantity0", "15");

		assertThat(stockOf(partId)).isEqualTo(15);
		assertThat(reservedOf(highLineId)).isEqualTo(10);
		assertThat(reservedOf(lowLineId)).isEqualTo(5);
	}

	/**
	 * A taken, unreported short order is topped up by the delivery; a
	 * completion-reported taken order (HIGH, so it would otherwise come first)
	 * receives nothing.
	 */
	@Test
	void deliveryTopsUpTakenUnreportedOrderButNotReportedOne() throws Exception {
		Long partId = seedPart("Tranzystor", 5, true, "Regał T1");
		Long projectId = seedProject("Zasilacz");
		Long reportedOrderId = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(3), Instant.now());
		Long reportedLineId = seedOrderLine(reportedOrderId, partId, 10, 1);
		Long takenOrderId = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(5), Instant.now());
		Long takenLineId = seedOrderLine(takenOrderId, partId, 10, 4);
		MockHttpSession session = technicianSession();
		pick(session, reportedOrderId, reportedLineId, 1);
		reportCompletion(session, reportedOrderId);
		pick(session, takenOrderId, takenLineId, 2);
		assertThat(stockOf(partId)).isEqualTo(2);
		assertThat(reservedOf(takenLineId)).isEqualTo(2);

		receive(session, "rows", "5", "partId0", partId.toString(), "quantity0", "5");

		assertThat(stockOf(partId)).isEqualTo(7);
		// Unmet = 10 required - 2 picked - 2 reserved = 6; the pool has 7 - 2 = 5 left.
		assertThat(reservedOf(takenLineId)).isEqualTo(7);
		assertThat(pickedOf(takenLineId)).isEqualTo(2);
		assertThat(reservedOf(reportedLineId)).isZero();
		assertThat(pickedOf(reportedLineId)).isEqualTo(1);
	}

	@Test
	void twoSuccessiveDeliveriesOnTheSamePartBothLandAndReallocate() throws Exception {
		Long partId = seedPart("Przekaźnik", 0, true, "Regał P1");
		Long orderId = seedOrder(seedProject("Automat"), Priority.NORMAL, LocalDate.now().plusDays(4),
				Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 20, 0);
		MockHttpSession session = technicianSession();

		receive(session, "rows", "5", "partId0", partId.toString(), "quantity0", "5");
		assertThat(stockOf(partId)).isEqualTo(5);
		assertThat(reservedOf(lineId)).isEqualTo(5);

		receive(session, "rows", "5", "partId0", partId.toString(), "quantity0", "7");
		assertThat(stockOf(partId)).isEqualTo(12);
		assertThat(reservedOf(lineId)).isEqualTo(12);
	}

	@Test
	void duplicateRowsOfOnePartAreSummedAndBothLocationsAdded() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "50", "location0", "Regał C1",
				"partId1", resistorId.toString(), "quantity1", "30", "location1", "Regał C2");

		assertThat(stockOf(resistorId)).isEqualTo(120);
		assertThat(locationsOf(resistorId)).containsExactly("Regał A1", "Regał C1", "Regał C2");
	}

	@Test
	void existingLocationIsNotDuplicatedAndMissingLocationLeavesLocationsUnchanged() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "10", "location0", "  Regał A1  ",
				"partId1", capacitorId.toString(), "quantity1", "3");

		assertThat(stockOf(resistorId)).isEqualTo(50);
		assertThat(stockOf(capacitorId)).isEqualTo(10);
		assertThat(locationsOf(resistorId)).containsExactly("Regał A1");
		assertThat(locationsOf(capacitorId)).containsExactly("Regał B2");
	}

	@Test
	void locationsAreMatchedIgnoringCase() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "5", "location0", "regał a1",
				"partId1", capacitorId.toString(), "quantity1", "1", "location1", "Regał C1",
				"partId2", capacitorId.toString(), "quantity2", "1", "location2", "REGAŁ c1");

		assertThat(stockOf(resistorId)).isEqualTo(45);
		assertThat(stockOf(capacitorId)).isEqualTo(9);
		assertThat(locationsOf(resistorId)).containsExactly("Regał A1");
		assertThat(locationsOf(capacitorId)).containsExactly("Regał B2", "Regał C1");
	}

	@Test
	void locationsPastedWithNonBreakingSpacesMatchTypedOnes() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "5", "location0", "Regał\u00A0A1\u00A0",
				"partId1", capacitorId.toString(), "quantity1", "1", "location1", "Regał C1\u00A0",
				"partId2", capacitorId.toString(), "quantity2", "1", "location2", "Regał\u202FC1");

		assertThat(stockOf(resistorId)).isEqualTo(45);
		assertThat(stockOf(capacitorId)).isEqualTo(9);
		assertThat(locationsOf(resistorId)).containsExactly("Regał A1");
		assertThat(locationsOf(capacitorId)).containsExactly("Regał B2", "Regał C1");
	}

	@Test
	void locationsPastedWithZeroWidthCharactersMatchTypedOnes() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "5", "location0", "Regał A1​",
				"partId1", capacitorId.toString(), "quantity1", "1", "location1", "﻿regał b2");

		assertThat(stockOf(resistorId)).isEqualTo(45);
		assertThat(stockOf(capacitorId)).isEqualTo(8);
		assertThat(locationsOf(resistorId)).containsExactly("Regał A1");
		assertThat(locationsOf(capacitorId)).containsExactly("Regał B2");
	}

	@Test
	void technicianCanReceiveIntoAnInactivePart() throws Exception {
		MockHttpSession session = technicianSession();

		receive(session, "rows", "5", "partId0", inactiveId.toString(), "quantity0", "5", "location0", "Regał Z8");

		assertThat(stockOf(inactiveId)).isEqualTo(8);
		assertThat(locationsOf(inactiveId)).containsExactly("Regał Z8", "Regał Z9");
		assertThat(jdbcTemplate.queryForObject("SELECT active FROM parts WHERE id = ?", Boolean.class, inactiveId))
			.isFalse();
	}

	@Test
	void deliveryOverflowingStockIsRejectedAndWritesNothing() throws Exception {
		jdbcTemplate.update("UPDATE parts SET quantity = ? WHERE id = ?", Integer.MAX_VALUE - 5, resistorId);
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Stan części „Rezystor 10k” przekroczyłby dopuszczalny zakres.",
				"rows", "5",
				"partId0", capacitorId.toString(), "quantity0", "3", "location0", "Regał N1",
				"partId1", resistorId.toString(), "quantity1", "10", "location1", "Regał N2");

		assertThat(html).contains(inputValue("quantity1", "10"));
		assertThat(stockOf(resistorId)).isEqualTo(Integer.MAX_VALUE - 5);
		assertThat(locationsOf(capacitorId)).containsExactly("Regał B2");
	}

	@Test
	void unknownPartIsRejectedAndNoOtherRowIsWritten() throws Exception {
		Long unknownId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM parts", Long.class);
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Jedna z wybranych części nie istnieje.",
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "10", "location0", "Regał N1",
				"partId1", unknownId.toString(), "quantity1", "4");

		assertThat(html).contains(inputValue("quantity0", "10"));
		assertThat(html).contains(inputValue("location0", "Regał N1"));
		assertThat(stockOf(resistorId)).isEqualTo(40);
	}

	/**
	 * Stands in for a concurrent manager edit adding the same location: a
	 * throwaway trigger makes the receipt's location insert fail with a
	 * unique violation at flush, which must roll back the stock increments
	 * too and re-render the form instead of surfacing a 500.
	 */
	@Test
	void constraintViolationDuringWriteReRendersWithSaveFailedAndWritesNothing() throws Exception {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_delivery_location_conflict() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					RAISE unique_violation USING MESSAGE = 'simulated concurrent location insert';
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_delivery_location_conflict_trigger BEFORE INSERT ON part_locations "
				+ "FOR EACH ROW EXECUTE FUNCTION test_delivery_location_conflict()");
		MockHttpSession session = technicianSession();

		String html = assertRejected(session, "Nie udało się zapisać dostawy. Spróbuj ponownie.",
				"rows", "5",
				"partId0", capacitorId.toString(), "quantity0", "3",
				"partId1", resistorId.toString(), "quantity1", "10", "location1", "Regał N2");

		assertThat(html).contains(inputValue("location1", "Regał N2"));
		assertThat(stockOf(capacitorId)).isEqualTo(7);
		assertThat(stockOf(resistorId)).isEqualTo(40);
	}

	@Test
	void successfulReceiptRedirectsToPartsWithSummary() throws Exception {
		MockHttpSession session = managerSession();
		String summary = "Przyjęto dostawę: 2 poz., 1083 szt.";

		MvcResult result = mockMvc.perform(receipt(session,
				"rows", "5",
				"partId0", resistorId.toString(), "quantity0", "50", "location0", "Regał A2",
				"partId1", resistorId.toString(), "quantity1", "30",
				"partId2", inactiveId.toString(), "quantity2", "1003"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"))
			.andExpect(flash().attribute("deliveryReceived", summary))
			.andReturn();

		mockMvc.perform(get("/parts").session(session).flashAttrs(result.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(summary)))
			.andExpect(content().string(containsString("/deliveries/new")));
		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Przyjmij dostawę")))
			.andExpect(content().string(containsString("/deliveries/new")));
	}

	@Test
	void technicianSeesReceiptLinksOnDashboardAndParts() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/deliveries/new\"")));
		mockMvc.perform(get("/parts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/deliveries/new\"")));
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

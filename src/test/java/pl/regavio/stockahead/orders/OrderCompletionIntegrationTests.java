package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code PickingController}'s completion-report action: a taken,
 * OPEN order reported by either role (stamping
 * {@code completion_reported_at}/{@code completion_reported_by} with the
 * reporting account) with the built count it sends ({@code built_units}:
 * 0, full or partial; a missing, non-integer, negative or too-large count is
 * rejected and leaves the order unreported), rejection of a report on an
 * untaken order, a repeated
 * report, and a report on a non-OPEN order (all leaving the report columns
 * untouched), 404 for an unknown order, the login redirect for an
 * unauthenticated request, and the picking views reflecting the report
 * (button only when allowed, pending notice and no pick forms afterwards, a
 * marker on the list), plus {@code OrderController}'s manager confirm/reject
 * actions: confirm completes the order and hands its released reservation to
 * the next open order (and off the shopping list), reject clears the report
 * and picking resumes, both refuse an unreported order, both are 403 for a
 * technician, and the report-reject-report-confirm cycle works on one order.
 * Also proves the taken-order top-up end to end: confirm hands released
 * units to a short taken order (which then picks them), a reported order is
 * skipped until reject catches it up in allocation order, and a new lower
 * priority order never takes units ahead of a short taken order.
 * Against real Postgres via Testcontainers,
 * deliberately not {@code @Transactional} so the V8 constraints fire for
 * real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompletionIntegrationTests {

	/** Report/completion moments render as date + minute, no seconds or zone. */
	private static final String MINUTE_PATTERN = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}";

	private static final String MANAGER_EMAIL = "order-completion-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-completion-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String REPORT_BUTTON = "Zgłoś zakończenie";

	private static final String PENDING_NOTICE = "Zgłoszone do potwierdzenia";

	private static final String PICK_BUTTON = "Pobierz";

	private static final String ORDER_NOT_OPEN_ERROR = "Zlecenie nie jest już otwarte.";

	private static final String NOT_TAKEN_ERROR = "Nie można zgłosić zakończenia zlecenia, które nie zostało podjęte.";

	private static final String ALREADY_REPORTED_ERROR = "Zlecenie zostało już zgłoszone jako zakończone.";

	private static final String NOT_REPORTED_ERROR = "Zlecenie nie oczekuje na potwierdzenie zakończenia.";

	private static final String BUILT_UNITS_NOT_INTEGER_ERROR =
			"Podaj liczbę zbudowanych sztuk (liczba całkowita, co najmniej 0).";

	private static final String BUILT_UNITS_TOO_MANY_ERROR =
			"Liczba zbudowanych sztuk nie może przekroczyć liczby sztuk w zleceniu (5).";

	private static final String CONFIRM_BUTTON = "Potwierdź zakończenie";

	private static final String REJECT_BUTTON = "Odrzuć zgłoszenie";

	private static final String PENDING_SECTION = "Do potwierdzenia";

	private static final String OPEN_SECTION = "Otwarte zlecenia";

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

	private Long projectId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject("Order Completion Board");
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
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
	}

	// ---- fixtures -----------------------------------------------------

	private void seedAccount(String email, Role role) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
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

	private Long accountIdOf(String email) {
		return accountRepository.findByEmail(email).orElseThrow().getId();
	}

	private Long seedProject(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, name));
	}

	private Long seedPart(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	private Long seedOrder() {
		return seedOrder(projectId);
	}

	private Long seedOrder(Long orderProjectId) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, 'NORMAL', ?, ?) RETURNING id",
				Long.class, orderProjectId, LocalDate.now().plusDays(7), Timestamp.from(Instant.now())));
	}

	/** An OPEN order for {@code quantityUnits} units of the default project. */
	private Long seedOrderWithUnits(int quantityUnits) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, 'NORMAL', ?, ?) RETURNING id",
				Long.class, projectId, quantityUnits, LocalDate.now().plusDays(7), Timestamp.from(Instant.now())));
	}

	/**
	 * An OPEN order with an explicit priority and {@code created_at}, so tests
	 * that depend on allocation order never tie on a shared {@code now()}.
	 */
	private Long seedOrder(Long orderProjectId, Priority priority, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, orderProjectId, priority.name(), LocalDate.now().plusDays(7), Timestamp.from(createdAt)));
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

	private void report(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));
	}

	private void confirm(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/confirm-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders"));
	}

	private void reject(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/reject-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders"));
	}

	/**
	 * Raises a part's stock directly, standing in for a future delivery or
	 * stock correction (no such endpoint exists yet).
	 */
	private void raiseStock(Long partId, int delta) {
		jdbcTemplate.update("UPDATE parts SET quantity = quantity + ? WHERE id = ?", delta, partId);
	}

	private String statusOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private Timestamp completedAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT completed_at FROM orders WHERE id = ?", Timestamp.class,
				orderId);
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int partQuantityOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private Timestamp completionReportedAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT completion_reported_at FROM orders WHERE id = ?",
				Timestamp.class, orderId);
	}

	private Long completionReportedByOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT completion_reported_by FROM orders WHERE id = ?", Long.class,
				orderId);
	}

	private Integer builtUnitsOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT built_units FROM orders WHERE id = ?", Integer.class, orderId);
	}

	// ---- happy path -----------------------------------------------------

	@Test
	void technicianReportsTakenOrderStampingReportAndReporter() throws Exception {
		Long partId = seedPart("Report Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession session = technicianSession();

		pick(session, orderId, lineId, 2);

		mockMvc.perform(get("/picking/{id}", orderId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(REPORT_BUTTON)))
			.andExpect(content().string(not(containsString(PENDING_NOTICE))));

		Instant before = Instant.now();
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));

		Timestamp reportedAt = completionReportedAtOf(orderId);
		assertThat(reportedAt).isNotNull();
		assertThat(reportedAt.toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(TECHNICIAN_EMAIL));
		assertThat(builtUnitsOf(orderId)).isEqualTo(1);

		mockMvc.perform(get("/picking/{id}", orderId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PENDING_NOTICE)))
			.andExpect(content().string(not(containsString(REPORT_BUTTON))))
			.andExpect(content().string(not(containsString(PICK_BUTTON))));
	}

	@Test
	void managerReportsTakenOrderStampingThemAsReporter() throws Exception {
		Long partId = seedPart("Manager Report Capacitor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession session = managerSession();

		pick(session, orderId, lineId, 5);

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));

		assertThat(completionReportedAtOf(orderId)).isNotNull();
		assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(MANAGER_EMAIL));
		assertThat(builtUnitsOf(orderId)).isEqualTo(1);
	}

	// ---- built count --------------------------------------------------------

	@Test
	void technicianReportPersistsZeroFullAndPartialBuiltCounts() throws Exception {
		MockHttpSession technician = technicianSession();
		for (int builtUnits : new int[] { 0, 5, 3 }) {
			Long partId = seedPart("Built Count Resistor " + builtUnits, 10);
			Long orderId = seedOrderWithUnits(5);
			Long lineId = seedOrderLine(orderId, partId, 5, 5);
			pick(technician, orderId, lineId, 1);

			mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician)
				.with(csrf())
				.param("builtUnits", Integer.toString(builtUnits)))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/picking/" + orderId));

			assertThat(completionReportedAtOf(orderId)).isNotNull();
			assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(TECHNICIAN_EMAIL));
			assertThat(builtUnitsOf(orderId)).isEqualTo(builtUnits);

			mockMvc.perform(get("/picking/{id}", orderId).session(technician))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("zbudowano " + builtUnits + " z 5")));
		}
	}

	@Test
	void invalidBuiltCountIsRejectedAndLeavesOrderUnreported() throws Exception {
		Long partId = seedPart("Invalid Count Resistor", 10);
		Long orderId = seedOrderWithUnits(5);
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession technician = technicianSession();
		pick(technician, orderId, lineId, 1);

		String[][] cases = {
				{ null, BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "", BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "abc", BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "2.5", BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "-1", BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "99999999999", BUILT_UNITS_NOT_INTEGER_ERROR },
				{ "6", BUILT_UNITS_TOO_MANY_ERROR },
		};
		for (String[] testCase : cases) {
			// Each value twice: a rejected report must leave nothing behind for a retry to trip on.
			for (int attempt = 0; attempt < 2; attempt++) {
				var request = post("/picking/{orderId}/report-completion", orderId).session(technician).with(csrf());
				if (testCase[0] != null) {
					request.param("builtUnits", testCase[0]);
				}
				mockMvc.perform(request)
					.andExpect(status().isOk())
					.andExpect(content().string(containsString(testCase[1])));

				assertThat(completionReportedAtOf(orderId)).isNull();
				assertThat(completionReportedByOf(orderId)).isNull();
				assertThat(builtUnitsOf(orderId)).isNull();
			}
		}

		// The order is still reportable with a valid count afterwards.
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician)
			.with(csrf())
			.param("builtUnits", "4"))
			.andExpect(status().is3xxRedirection());
		assertThat(builtUnitsOf(orderId)).isEqualTo(4);
	}

	// ---- rejections -------------------------------------------------------

	@Test
	void reportOnUntakenOrderIsRejectedAndButtonIsAbsent() throws Exception {
		Long partId = seedPart("Untaken Resistor", 10);
		Long orderId = seedOrder();
		seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/picking/{id}", orderId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(REPORT_BUTTON))))
			.andExpect(content().string(containsString(PICK_BUTTON)));

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(NOT_TAKEN_ERROR)));

		assertThat(completionReportedAtOf(orderId)).isNull();
		assertThat(completionReportedByOf(orderId)).isNull();
	}

	@Test
	void secondReportIsRejectedAndKeepsTheFirstReport() throws Exception {
		Long partId = seedPart("Twice Reported Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 1);
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection());
		Timestamp firstReportedAt = completionReportedAtOf(orderId);
		Long technicianId = accountIdOf(TECHNICIAN_EMAIL);

		// Rejected every time and for either role, never overwriting the first report.
		for (MockHttpSession session : new MockHttpSession[] { technician, manager }) {
			for (int attempt = 0; attempt < 2; attempt++) {
				mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString(ALREADY_REPORTED_ERROR)));

				assertThat(completionReportedAtOf(orderId)).isEqualTo(firstReportedAt);
				assertThat(completionReportedByOf(orderId)).isEqualTo(technicianId);
			}
		}
	}

	@Test
	void reportOnCancelledOrCompletedOrderIsRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		Long cancelledPart = seedPart("Cancelled Order Part", 10);
		Long cancelledOrder = seedOrder();
		Long cancelledLine = seedOrderLine(cancelledOrder, cancelledPart, 5, 5);
		pick(session, cancelledOrder, cancelledLine, 1);
		mockMvc.perform(post("/orders/{id}/cancel", cancelledOrder).session(manager)
			.with(csrf())
			.param("seenPicked_" + cancelledLine, "1")
			.param("returned_" + cancelledLine, "0"))
			.andExpect(status().is3xxRedirection());
		assertThat(statusOf(cancelledOrder)).isEqualTo("CANCELLED");

		mockMvc.perform(post("/picking/{orderId}/report-completion", cancelledOrder).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));
		assertThat(completionReportedAtOf(cancelledOrder)).isNull();
		assertThat(completionReportedByOf(cancelledOrder)).isNull();

		Long completedPart = seedPart("Completed Order Part", 10);
		Long completedOrder = seedOrder();
		Long completedLine = seedOrderLine(completedOrder, completedPart, 5, 5);
		pick(session, completedOrder, completedLine, 5);
		report(session, completedOrder);
		confirm(manager, completedOrder);
		assertThat(statusOf(completedOrder)).isEqualTo("COMPLETED");
		Timestamp completedReportedAt = completionReportedAtOf(completedOrder);

		mockMvc.perform(post("/picking/{orderId}/report-completion", completedOrder).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));
		assertThat(completionReportedAtOf(completedOrder)).isEqualTo(completedReportedAt);
	}

	@Test
	void reportOnUnknownOrderIs404() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/picking/{orderId}/report-completion", 999_999_999L).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().isNotFound());
	}

	@Test
	void unauthenticatedReportRedirectsToLoginAndChangesNothing() throws Exception {
		Long partId = seedPart("Anonymous Report Part", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		pick(technicianSession(), orderId, lineId, 1);

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));

		assertThat(completionReportedAtOf(orderId)).isNull();
	}

	// ---- picking list -------------------------------------------------------

	@Test
	void pickingListMarksOnlyReportedOrders() throws Exception {
		Long partId = seedPart("List Marker Part", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/picking").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Zgłoszone"))));

		pick(session, orderId, lineId, 1);
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/picking").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zgłoszone")));
	}

	// ---- manager confirm -------------------------------------------------

	@Test
	void confirmCompletesOrderAndHandsReleasedUnitsToTheNextOrder() throws Exception {
		Long partId = seedPart("Shared Resistor", 6);
		Long orderA = seedOrder();
		Long lineA = seedOrderLine(orderA, partId, 10, 6);
		Long orderB = seedOrder(seedProject("Next In Line Board"));
		Long lineB = seedOrderLine(orderB, partId, 4, 0);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderA, lineA, 2);
		report(technician, orderA);
		assertThat(reservedOf(lineA)).isEqualTo(4);
		assertThat(reservedOf(lineB)).isZero();

		mockMvc.perform(get("/orders/{id}", orderA).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(TECHNICIAN_EMAIL)))
			.andExpect(content().string(matchesPattern("(?s).*, <span>" + MINUTE_PATTERN + "</span>.*")))
			.andExpect(content().string(containsString("Shared Resistor: pobrano 2 z 10")))
			.andExpect(content().string(containsString("Brakująca ilość")))
			.andExpect(content().string(containsString(CONFIRM_BUTTON)))
			.andExpect(content().string(containsString(REJECT_BUTTON)));
		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Order Completion Board")))
			.andExpect(content().string(containsString("Next In Line Board")));

		Instant before = Instant.now();
		confirm(manager, orderA);

		assertThat(statusOf(orderA)).isEqualTo("COMPLETED");
		assertThat(completedAtOf(orderA)).isNotNull();
		assertThat(completedAtOf(orderA).toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(reservedOf(lineA)).isZero();
		assertThat(pickedOf(lineA)).isEqualTo(2);
		assertThat(partQuantityOf(partId)).isEqualTo(4);
		assertThat(reservedOf(lineB)).isEqualTo(4);

		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Order Completion Board"))))
			.andExpect(content().string(containsString("Brak braków.")));
		mockMvc.perform(get("/orders").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Order Completion Board"))))
			.andExpect(content().string(containsString("Next In Line Board")));
		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Order Completion Board"))));
		mockMvc.perform(get("/orders/{id}", orderA).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(matchesPattern("(?s).*Zakończone <span>" + MINUTE_PATTERN + "</span>.*")))
			.andExpect(content().string(not(containsString("Brakująca ilość"))))
			.andExpect(content().string(not(containsString(CONFIRM_BUTTON))))
			.andExpect(content().string(not(containsString(REJECT_BUTTON))));
	}

	@Test
	void confirmAndRejectWithoutPendingReportAreRejected() throws Exception {
		Long partId = seedPart("Unreported Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		pick(technician, orderId, lineId, 1);

		mockMvc.perform(get("/orders/{id}", orderId).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(CONFIRM_BUTTON))))
			.andExpect(content().string(not(containsString(REJECT_BUTTON))));

		for (String action : new String[] { "confirm-completion", "reject-completion" }) {
			for (int attempt = 0; attempt < 2; attempt++) {
				mockMvc.perform(post("/orders/{id}/" + action, orderId).session(manager).with(csrf()))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString(NOT_REPORTED_ERROR)));

				assertThat(statusOf(orderId)).isEqualTo("OPEN");
				assertThat(completedAtOf(orderId)).isNull();
				assertThat(reservedOf(lineId)).isEqualTo(4);
			}
		}
	}

	@Test
	void confirmAndRejectOnCompletedOrderAreRejected() throws Exception {
		Long partId = seedPart("Already Completed Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		pick(technician, orderId, lineId, 5);
		report(technician, orderId);
		confirm(manager, orderId);
		Timestamp completedAt = completedAtOf(orderId);
		Timestamp reportedAt = completionReportedAtOf(orderId);

		for (String action : new String[] { "confirm-completion", "reject-completion" }) {
			mockMvc.perform(post("/orders/{id}/" + action, orderId).session(manager).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(NOT_REPORTED_ERROR)));

			assertThat(statusOf(orderId)).isEqualTo("COMPLETED");
			assertThat(completedAtOf(orderId)).isEqualTo(completedAt);
			assertThat(completionReportedAtOf(orderId)).isEqualTo(reportedAt);
		}
	}

	@Test
	void confirmAndRejectOnUnknownOrderAre404() throws Exception {
		MockHttpSession manager = managerSession();

		for (String action : new String[] { "confirm-completion", "reject-completion" }) {
			mockMvc.perform(post("/orders/{id}/" + action, 999_999_999L).session(manager).with(csrf()))
				.andExpect(status().isNotFound());
		}
	}

	// ---- manager reject ---------------------------------------------------

	@Test
	void rejectClearsReportAndPickingResumes() throws Exception {
		Long partId = seedPart("Rejected Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 1);
		report(technician, orderId);

		reject(manager, orderId);

		assertThat(statusOf(orderId)).isEqualTo("OPEN");
		assertThat(completionReportedAtOf(orderId)).isNull();
		assertThat(completionReportedByOf(orderId)).isNull();
		assertThat(reservedOf(lineId)).isEqualTo(4);

		mockMvc.perform(get("/picking/{id}", orderId).session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PENDING_NOTICE))))
			.andExpect(content().string(containsString(PICK_BUTTON)));

		pick(technician, orderId, lineId, 2);
		assertThat(reservedOf(lineId)).isEqualTo(2);
		assertThat(pickedOf(lineId)).isEqualTo(3);
		assertThat(partQuantityOf(partId)).isEqualTo(7);
	}

	@Test
	void reportRejectPickReportConfirmCycleSucceeds() throws Exception {
		Long partId = seedPart("Cycle Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 1);
		for (int round = 0; round < 2; round++) {
			report(technician, orderId);
			reject(manager, orderId);
			assertThat(completionReportedAtOf(orderId)).isNull();
			pick(technician, orderId, lineId, 1);
		}
		assertThat(pickedOf(lineId)).isEqualTo(3);
		assertThat(reservedOf(lineId)).isEqualTo(2);

		report(technician, orderId);
		confirm(manager, orderId);

		assertThat(statusOf(orderId)).isEqualTo("COMPLETED");
		assertThat(completedAtOf(orderId)).isNotNull();
		assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(TECHNICIAN_EMAIL));
		assertThat(reservedOf(lineId)).isZero();
		assertThat(pickedOf(lineId)).isEqualTo(3);
		assertThat(partQuantityOf(partId)).isEqualTo(7);
	}

	@Test
	void technicianGets403OnConfirmAndReject() throws Exception {
		Long partId = seedPart("Forbidden Resistor", 10);
		Long orderId = seedOrder();
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession technician = technicianSession();
		pick(technician, orderId, lineId, 1);
		report(technician, orderId);
		Timestamp reportedAt = completionReportedAtOf(orderId);

		for (String action : new String[] { "confirm-completion", "reject-completion" }) {
			mockMvc.perform(post("/orders/{id}/" + action, orderId).session(technician).with(csrf()))
				.andExpect(status().isForbidden());

			assertThat(statusOf(orderId)).isEqualTo("OPEN");
			assertThat(completionReportedAtOf(orderId)).isEqualTo(reportedAt);
			assertThat(reservedOf(lineId)).isEqualTo(4);
		}
	}

	// ---- orders list ------------------------------------------------------

	@Test
	void ordersListShowsReportedOrderUnderPendingSectionOnly() throws Exception {
		Long partId = seedPart("Section Resistor", 20);
		Long reportedOrder = seedOrder(seedProject("Reported Board"));
		Long reportedLine = seedOrderLine(reportedOrder, partId, 5, 5);
		Long openOrder = seedOrder(seedProject("Still Open Board"));
		seedOrderLine(openOrder, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/orders").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PENDING_SECTION))));

		pick(technician, reportedOrder, reportedLine, 1);
		report(technician, reportedOrder);

		String html = mockMvc.perform(get("/orders").session(manager))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		int pendingSection = html.indexOf(PENDING_SECTION);
		int openSection = html.indexOf(OPEN_SECTION);
		assertThat(html).containsPattern("<td>" + MINUTE_PATTERN + "</td>");
		assertThat(pendingSection).isNotNegative();
		assertThat(openSection).isGreaterThan(pendingSection);
		int reportedPosition = html.indexOf("Reported Board");
		assertThat(reportedPosition).isBetween(pendingSection, openSection);
		assertThat(html.indexOf("Reported Board", openSection)).isNegative();
		assertThat(html.indexOf("Still Open Board")).isGreaterThan(openSection);
	}

	// ---- taken-order top-up -------------------------------------------------

	@Test
	void confirmHandsReleasedUnitsToAShortTakenOrder() throws Exception {
		Long partId = seedPart("Top-Up Resistor", 10);
		Instant now = Instant.now();
		Long orderA = seedOrder(projectId, Priority.HIGH, now.minusSeconds(20));
		Long lineA = seedOrderLine(orderA, partId, 6, 6);
		Long orderB = seedOrder(seedProject("Short Taken Board"), Priority.NORMAL, now.minusSeconds(10));
		Long lineB = seedOrderLine(orderB, partId, 8, 4);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderA, lineA, 1);
		pick(technician, orderB, lineB, 1);
		report(technician, orderA);
		assertThat(partQuantityOf(partId)).isEqualTo(8);
		assertThat(reservedOf(lineB)).isEqualTo(3);

		confirm(manager, orderA);

		// Pool after confirm: stock 8 - B's protected 3 = 5; B is short by 8 - 1 - 3 = 4.
		assertThat(statusOf(orderA)).isEqualTo("COMPLETED");
		assertThat(reservedOf(lineA)).isZero();
		assertThat(reservedOf(lineB)).isEqualTo(7);
		assertThat(pickedOf(lineB)).isEqualTo(1);

		mockMvc.perform(get("/picking/{id}", orderB).session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PICK_BUTTON)));

		// Two real picks on the topped-up line, the second taking the remainder.
		pick(technician, orderB, lineB, 2);
		assertThat(reservedOf(lineB)).isEqualTo(5);
		assertThat(pickedOf(lineB)).isEqualTo(3);
		pick(technician, orderB, lineB, 5);
		assertThat(reservedOf(lineB)).isZero();
		assertThat(pickedOf(lineB)).isEqualTo(8);
		assertThat(partQuantityOf(partId)).isEqualTo(1);
	}

	@Test
	void reportedShortOrderIsSkippedUntilRejectCatchesItUp() throws Exception {
		Long partId = seedPart("Reported Top-Up Resistor", 10);
		Instant now = Instant.now();
		Long orderA = seedOrder(projectId, Priority.HIGH, now.minusSeconds(30));
		Long lineA = seedOrderLine(orderA, partId, 6, 6);
		Long orderB = seedOrder(seedProject("Reported Short Board"), Priority.NORMAL, now.minusSeconds(20));
		Long lineB = seedOrderLine(orderB, partId, 6, 4);
		Long orderC = seedOrder(seedProject("Waiting Low Board"), Priority.LOW, now.minusSeconds(10));
		Long lineC = seedOrderLine(orderC, partId, 4, 0);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderA, lineA, 1);
		pick(technician, orderB, lineB, 1);
		report(technician, orderB);
		report(technician, orderA);
		assertThat(partQuantityOf(partId)).isEqualTo(8);
		assertThat(reservedOf(lineB)).isEqualTo(3);

		confirm(manager, orderA);

		// B is reported: it keeps its 3 and gets nothing; the freed units go to C.
		assertThat(reservedOf(lineB)).isEqualTo(3);
		assertThat(reservedOf(lineC)).isEqualTo(4);

		reject(manager, orderB);

		// Pool: 8 - B's 3 = 5. B (NORMAL) before C (LOW): B tops up by 6 - 1 - 3 = 2,
		// C (not taken) is rebuilt from the remaining 3.
		assertThat(completionReportedAtOf(orderB)).isNull();
		assertThat(reservedOf(lineB)).isEqualTo(5);
		assertThat(reservedOf(lineC)).isEqualTo(3);
		assertThat(reservedOf(lineB) + reservedOf(lineC)).isLessThanOrEqualTo(partQuantityOf(partId));

		mockMvc.perform(get("/picking/{id}", orderB).session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PENDING_NOTICE))))
			.andExpect(content().string(containsString(PICK_BUTTON)));
		pick(technician, orderB, lineB, 2);
		pick(technician, orderB, lineB, 3);
		assertThat(reservedOf(lineB)).isZero();
		assertThat(pickedOf(lineB)).isEqualTo(6);
		assertThat(partQuantityOf(partId)).isEqualTo(3);
		assertThat(reservedOf(lineC)).isEqualTo(3);
	}

	@Test
	void newLowPriorityOrderDoesNotTakeUnitsAShortTakenHigherOrderNeeds() throws Exception {
		Long partId = seedPart("Creation Top-Up Resistor", 10);
		Instant now = Instant.now();
		Long orderA = seedOrder(projectId, Priority.HIGH, now.minusSeconds(20));
		Long lineA = seedOrderLine(orderA, partId, 6, 6);
		Long orderB = seedOrder(seedProject("Very Short Board"), Priority.NORMAL, now.minusSeconds(10));
		Long lineB = seedOrderLine(orderB, partId, 12, 4);
		Long lowProjectId = seedProject("Late Low Board");
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, 5)", lowProjectId,
				partId));
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderA, lineA, 1);
		pick(technician, orderB, lineB, 1);
		report(technician, orderA);
		confirm(manager, orderA);

		// Pool after confirm: 8 - 3 = 5, all of it to B (short by 12 - 1 - 3 = 8).
		assertThat(reservedOf(lineB)).isEqualTo(8);
		assertThat(partQuantityOf(partId)).isEqualTo(8);

		// Stand-in for a delivery; the creation event below is what reallocates it.
		raiseStock(partId, 5);

		mockMvc.perform(post("/orders").session(manager)
			.with(csrf())
			.param("projectId", lowProjectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "LOW")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().is3xxRedirection());
		Long newLineId = jdbcTemplate.queryForObject(
				"SELECT ol.id FROM order_lines ol JOIN orders o ON o.id = ol.order_id WHERE o.project_id = ?",
				Long.class, lowProjectId);

		// Pool: 13 - B's 8 = 5. B (NORMAL) tops up its remaining 3 first; the LOW order gets 2.
		assertThat(reservedOf(lineB)).isEqualTo(11);
		assertThat(pickedOf(lineB)).isEqualTo(1);
		assertThat(reservedOf(newLineId)).isEqualTo(2);
		assertThat(reservedOf(lineB) + reservedOf(newLineId)).isLessThanOrEqualTo(partQuantityOf(partId));
	}

}

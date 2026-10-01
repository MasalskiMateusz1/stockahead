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
 * reporting account), rejection of a report on an untaken order, a repeated
 * report, and a report on a non-OPEN order (all leaving the report columns
 * untouched), 404 for an unknown order, the login redirect for an
 * unauthenticated request, and the picking views reflecting the report
 * (button only when allowed, pending notice and no pick forms afterwards, a
 * marker on the list), plus {@code OrderController}'s manager confirm/reject
 * actions: confirm completes the order and hands its released reservation to
 * the next open order (and off the shopping list), reject clears the report
 * and picking resumes, both refuse an unreported order, both are 403 for a
 * technician, and the report-reject-report-confirm cycle works on one order.
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

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity));
	}

	private void markTaken(Long orderId) {
		transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE orders SET taken_at = now() WHERE id = ?", orderId));
	}

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	private void report(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
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
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));

		Timestamp reportedAt = completionReportedAtOf(orderId);
		assertThat(reportedAt).isNotNull();
		assertThat(reportedAt.toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(TECHNICIAN_EMAIL));

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

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));

		assertThat(completionReportedAtOf(orderId)).isNotNull();
		assertThat(completionReportedByOf(orderId)).isEqualTo(accountIdOf(MANAGER_EMAIL));
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

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
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
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician).with(csrf()))
			.andExpect(status().is3xxRedirection());
		Timestamp firstReportedAt = completionReportedAtOf(orderId);
		Long technicianId = accountIdOf(TECHNICIAN_EMAIL);

		// Rejected every time and for either role, never overwriting the first report.
		for (MockHttpSession session : new MockHttpSession[] { technician, manager }) {
			for (int attempt = 0; attempt < 2; attempt++) {
				mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString(ALREADY_REPORTED_ERROR)));

				assertThat(completionReportedAtOf(orderId)).isEqualTo(firstReportedAt);
				assertThat(completionReportedByOf(orderId)).isEqualTo(technicianId);
			}
		}
	}

	@Test
	void reportOnCancelledOrCompletedOrderIsRejected() throws Exception {
		MockHttpSession session = technicianSession();
		Long technicianId = accountIdOf(TECHNICIAN_EMAIL);

		Long cancelledPart = seedPart("Cancelled Order Part", 10);
		Long cancelledOrder = seedOrder();
		seedOrderLine(cancelledOrder, cancelledPart, 5, 5);
		markTaken(cancelledOrder);
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate
			.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelledOrder));

		mockMvc.perform(post("/picking/{orderId}/report-completion", cancelledOrder).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));
		assertThat(completionReportedAtOf(cancelledOrder)).isNull();
		assertThat(completionReportedByOf(cancelledOrder)).isNull();

		Long completedPart = seedPart("Completed Order Part", 10);
		Long completedOrder = seedOrder();
		seedOrderLine(completedOrder, completedPart, 5, 0);
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"UPDATE orders SET taken_at = now(), completion_reported_at = now(), completion_reported_by = ?, "
						+ "status = 'COMPLETED', completed_at = now() WHERE id = ?",
				technicianId, completedOrder));
		Timestamp completedReportedAt = completionReportedAtOf(completedOrder);

		mockMvc.perform(post("/picking/{orderId}/report-completion", completedOrder).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));
		assertThat(completionReportedAtOf(completedOrder)).isEqualTo(completedReportedAt);
	}

	@Test
	void reportOnUnknownOrderIs404() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/picking/{orderId}/report-completion", 999_999_999L).session(session).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void unauthenticatedReportRedirectsToLoginAndChangesNothing() throws Exception {
		Long partId = seedPart("Anonymous Report Part", 10);
		Long orderId = seedOrder();
		seedOrderLine(orderId, partId, 5, 5);
		markTaken(orderId);

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).with(csrf()))
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
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()))
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
		assertThat(pendingSection).isNotNegative();
		assertThat(openSection).isGreaterThan(pendingSection);
		int reportedPosition = html.indexOf("Reported Board");
		assertThat(reportedPosition).isBetween(pendingSection, openSection);
		assertThat(html.indexOf("Reported Board", openSection)).isNegative();
		assertThat(html.indexOf("Still Open Board")).isGreaterThan(openSection);
	}

}

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
 * marker on the list). Against real Postgres via Testcontainers,
 * deliberately not {@code @Transactional} so the V8 constraints fire for
 * real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompletionIntegrationTests {

	private static final String MANAGER_EMAIL = "order-completion-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-completion-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String REPORT_BUTTON = "Zgłoś zakończenie";

	private static final String PENDING_NOTICE = "Zgłoszone do potwierdzenia";

	private static final String PICK_BUTTON = "Pobierz";

	private static final String ORDER_NOT_OPEN_ERROR = "Zlecenie nie jest już otwarte.";

	private static final String NOT_TAKEN_ERROR = "Nie można zgłosić zakończenia zlecenia, które nie zostało podjęte.";

	private static final String ALREADY_REPORTED_ERROR = "Zlecenie zostało już zgłoszone jako zakończone.";

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
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, 'NORMAL', ?, ?) RETURNING id",
				Long.class, projectId, LocalDate.now().plusDays(7), Timestamp.from(Instant.now())));
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

}

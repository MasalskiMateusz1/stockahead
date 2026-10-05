package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
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
 * Covers {@code OrderController.assign}: the manager reassigns an untaken, a
 * taken (through the real pick endpoint) and a completion-reported order, the
 * reassign card staying visible after the order is taken while the
 * priority/date card is gone; a blank id unassigns; an inactive, unknown or
 * malformed account is refused with the assignee unchanged; a
 * {@code COMPLETED} or {@code CANCELLED} order is refused; reassignment leaves
 * every line's reservation and picks and the order's schedule and
 * {@code taken_at} untouched (it is not a reallocation event); an unknown
 * order is 404 and a technician gets 403. Against real Postgres via
 * Testcontainers, deliberately not {@code @Transactional} so every write
 * commits for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OrderAssignIntegrationTests {

	private static final String MANAGER_EMAIL = "order-assign-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-assign-technician@example.com";

	private static final String OTHER_TECHNICIAN_EMAIL = "order-assign-other-technician@example.com";

	private static final String INACTIVE_TECHNICIAN_EMAIL = "order-assign-inactive-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String ASSIGN_SECTION = "id=\"order-assign\"";

	private static final String CHANGE_SECTION = "id=\"order-change\"";

	private static final String ASSIGNEE_UNAVAILABLE_ERROR = "Wybrany technik jest nieaktywny lub nie istnieje.";

	private static final String NOT_ASSIGNABLE_ERROR = "Nie można zmienić technika zlecenia, które nie jest otwarte.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company company;

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
		projectId = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES ('Order Assign Board', true) RETURNING id", Long.class));
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
		accountRepository.findByEmail(OTHER_TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(INACTIVE_TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("OrderAssignIntegrationTests Co");
		}
		return company;
	}

	private Long seedAccount(String email, Role role, boolean active) {
		return companyFixtures.account(company(), email, PASSWORD, role, active).getId();
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
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

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	private void report(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session)
			.with(csrf())
			.param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection());
	}

	private void assign(MockHttpSession session, Long orderId, String assigneeId) throws Exception {
		mockMvc.perform(post("/orders/{id}/assign", orderId).session(session)
			.with(csrf())
			.param("assigneeId", assigneeId))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderId));
	}

	private Long assigneeIdOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT assignee_id FROM orders WHERE id = ?", Long.class, orderId);
	}

	private String statusOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private void setAssignee(Long orderId, Long accountId) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate
			.update("UPDATE orders SET assignee_id = ? WHERE id = ?", accountId, orderId));
	}

	/**
	 * Everything reassignment must leave alone: the order's schedule and
	 * {@code taken_at}, and every line's reservation and picks.
	 */
	private Map<String, Object> scheduleOf(Long orderId) {
		return jdbcTemplate.queryForMap(
				"SELECT priority, required_date, taken_at FROM orders WHERE id = ?", orderId);
	}

	private List<Map<String, Object>> linesOf(Long orderId) {
		return jdbcTemplate.queryForList(
				"SELECT id, reserved_quantity, picked_quantity FROM order_lines WHERE order_id = ? ORDER BY id",
				orderId);
	}

	// ---- reassignment -------------------------------------------------

	@Test
	void managerReassignsUntakenTakenAndReportedOrdersWithoutTouchingReservations() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		Long otherId = seedAccount(OTHER_TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		MockHttpSession technician = loginAs(TECHNICIAN_EMAIL);

		// Untaken.
		Long untakenPart = seedPart("Assign Untaken Resistor", 10);
		Long untaken = seedOrder();
		seedOrderLine(untaken, untakenPart, 5, 5);

		mockMvc.perform(get("/orders/{id}", untaken).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ASSIGN_SECTION)))
			.andExpect(content().string(containsString(CHANGE_SECTION)));

		Map<String, Object> untakenSchedule = scheduleOf(untaken);
		List<Map<String, Object>> untakenLines = linesOf(untaken);
		assign(manager, untaken, technicianId.toString());
		assertThat(assigneeIdOf(untaken)).isEqualTo(technicianId);
		assertThat(scheduleOf(untaken)).isEqualTo(untakenSchedule);
		assertThat(linesOf(untaken)).isEqualTo(untakenLines);

		// Taken: the reassign card stays while the priority/date card is gone.
		Long takenPart = seedPart("Assign Taken Capacitor", 10);
		Long taken = seedOrder();
		Long takenLine = seedOrderLine(taken, takenPart, 5, 5);
		setAssignee(taken, technicianId);
		pick(technician, taken, takenLine, 2);

		mockMvc.perform(get("/orders/{id}", taken).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ASSIGN_SECTION)))
			.andExpect(content().string(not(containsString(CHANGE_SECTION))))
			.andExpect(content().string(matchesPattern(
					"(?s).*<option value=\"" + technicianId + "\"\\s+selected=\"selected\">" + TECHNICIAN_EMAIL + ".*")));

		Map<String, Object> takenSchedule = scheduleOf(taken);
		List<Map<String, Object>> takenLines = linesOf(taken);
		assertThat(takenSchedule.get("taken_at")).isNotNull();
		assign(manager, taken, otherId.toString());
		assertThat(assigneeIdOf(taken)).isEqualTo(otherId);
		assertThat(scheduleOf(taken)).isEqualTo(takenSchedule);
		assertThat(linesOf(taken)).isEqualTo(takenLines);

		mockMvc.perform(get("/orders/{id}", taken).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(OTHER_TECHNICIAN_EMAIL)));

		// Completion reported.
		Long reportedPart = seedPart("Assign Reported Inductor", 10);
		Long reported = seedOrder();
		Long reportedLine = seedOrderLine(reported, reportedPart, 5, 5);
		pick(technician, reported, reportedLine, 5);
		report(technician, reported);

		Map<String, Object> reportedSchedule = scheduleOf(reported);
		List<Map<String, Object>> reportedLines = linesOf(reported);
		assign(manager, reported, managerId.toString());
		assertThat(assigneeIdOf(reported)).isEqualTo(managerId);
		assertThat(statusOf(reported)).isEqualTo("OPEN");
		assertThat(jdbcTemplate.queryForObject("SELECT completion_reported_at FROM orders WHERE id = ?",
				Timestamp.class, reported)).isNotNull();
		assertThat(scheduleOf(reported)).isEqualTo(reportedSchedule);
		assertThat(linesOf(reported)).isEqualTo(reportedLines);
	}

	@Test
	void blankAssigneeIdClearsTheAssignee() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		Long orderId = seedOrder();
		setAssignee(orderId, technicianId);

		assign(manager, orderId, "");

		assertThat(assigneeIdOf(orderId)).isNull();
	}

	@Test
	void orderWithoutLinesCanBeReassigned() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		Long orderId = seedOrder();

		assign(manager, orderId, technicianId.toString());

		assertThat(assigneeIdOf(orderId)).isEqualTo(technicianId);
	}

	// ---- rejections ---------------------------------------------------

	@Test
	void inactiveUnknownOrMalformedAssigneeIsRejectedAndAssigneeUnchanged() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		Long inactiveId = seedAccount(INACTIVE_TECHNICIAN_EMAIL, Role.TECHNICIAN, false);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		Long partId = seedPart("Assign Rejected Diode", 10);
		Long orderId = seedOrder();
		seedOrderLine(orderId, partId, 5, 5);
		setAssignee(orderId, technicianId);

		for (String assigneeIdValue : List.of(inactiveId.toString(), "999999999", "not-a-number")) {
			mockMvc.perform(post("/orders/{id}/assign", orderId).session(manager)
				.with(csrf())
				.param("assigneeId", assigneeIdValue))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(ASSIGNEE_UNAVAILABLE_ERROR)));

			assertThat(assigneeIdOf(orderId)).isEqualTo(technicianId);
		}
	}

	@Test
	void inactiveCurrentAssigneeIsNotPreselected() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long inactiveId = seedAccount(INACTIVE_TECHNICIAN_EMAIL, Role.TECHNICIAN, false);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		Long orderId = seedOrder();
		setAssignee(orderId, inactiveId);

		mockMvc.perform(get("/orders/{id}", orderId).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ASSIGN_SECTION)))
			.andExpect(content().string(not(containsString("value=\"" + inactiveId + "\""))))
			.andExpect(content().string(containsString("<option value=\"" + managerId + "\">" + MANAGER_EMAIL)));
	}

	@Test
	void completedOrCancelledOrderIsRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		MockHttpSession technician = loginAs(TECHNICIAN_EMAIL);

		Long cancelledOrder = seedOrder();
		mockMvc.perform(post("/orders/{id}/cancel", cancelledOrder).session(manager).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(statusOf(cancelledOrder)).isEqualTo("CANCELLED");

		Long completedPart = seedPart("Assign Completed Relay", 10);
		Long completedOrder = seedOrder();
		Long completedLine = seedOrderLine(completedOrder, completedPart, 5, 5);
		pick(technician, completedOrder, completedLine, 5);
		report(technician, completedOrder);
		mockMvc.perform(post("/orders/{id}/confirm-completion", completedOrder).session(manager).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(statusOf(completedOrder)).isEqualTo("COMPLETED");

		for (Long orderId : List.of(cancelledOrder, completedOrder)) {
			mockMvc.perform(get("/orders/{id}", orderId).session(manager))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(ASSIGN_SECTION))));

			mockMvc.perform(post("/orders/{id}/assign", orderId).session(manager)
				.with(csrf())
				.param("assigneeId", technicianId.toString()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(NOT_ASSIGNABLE_ERROR)));

			assertThat(assigneeIdOf(orderId)).isNull();
		}
	}

	@Test
	void unknownOrderIs404() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/orders/{id}/assign", 999_999_999L).session(manager)
			.with(csrf())
			.param("assigneeId", ""))
			.andExpect(status().isNotFound());
	}

	// ---- access -------------------------------------------------------

	@Test
	void technicianIsForbiddenAndNothingChanges() throws Exception {
		Long technicianId = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession technician = loginAs(TECHNICIAN_EMAIL);
		Long orderId = seedOrder();

		mockMvc.perform(post("/orders/{id}/assign", orderId).session(technician)
			.with(csrf())
			.param("assigneeId", technicianId.toString()))
			.andExpect(status().isForbidden());

		assertThat(assigneeIdOf(orderId)).isNull();
	}

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		Long orderId = seedOrder();

		mockMvc.perform(post("/orders/{id}/assign", orderId).with(csrf()).param("assigneeId", ""))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));

		assertThat(assigneeIdOf(orderId)).isNull();
	}

}

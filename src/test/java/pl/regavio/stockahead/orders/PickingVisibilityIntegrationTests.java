package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

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
 * Covers the per-viewer {@code GET /picking} list (FR-022): a technician sees
 * only the open orders assigned to them, whatever {@code assignee} they pass;
 * the manager sees every open order, narrowed by {@code assignee=<id>} to one
 * account's orders or by {@code assignee=none} to orders with no assignee or
 * a deactivated one, and a malformed or non-assignable value falls back to
 * every order with a 200. A reassignment of a taken order moves it from one
 * technician's list to the other's. Each order belongs to its own project, so
 * a project name in the page identifies exactly one order. Against real
 * Postgres via Testcontainers, deliberately not {@code @Transactional} so
 * every write commits for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PickingVisibilityIntegrationTests {

	private static final String MANAGER_EMAIL = "picking-visibility-manager@example.com";

	private static final String TECHNICIAN_A_EMAIL = "picking-visibility-technician-a@example.com";

	private static final String TECHNICIAN_B_EMAIL = "picking-visibility-technician-b@example.com";

	private static final String TECHNICIAN_C_EMAIL = "picking-visibility-technician-c@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String A_PROJECT = "Visibility Alpha Board";

	private static final String B_PROJECT = "Visibility Bravo Board";

	private static final String UNASSIGNED_PROJECT = "Visibility Unassigned Board";

	private static final String MANAGER_PROJECT = "Visibility Manager Board";

	private static final String C_PROJECT = "Visibility Charlie Board";

	private static final String FILTER_FORM = "name=\"assignee\"";

	private static final String TECHNIK_COLUMN = "<th>Technik</th>";

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

	private Long managerId;

	private Long technicianAId;

	private Long technicianBId;

	private Long orderA;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		technicianAId = seedAccount(TECHNICIAN_A_EMAIL, Role.TECHNICIAN);
		technicianBId = seedAccount(TECHNICIAN_B_EMAIL, Role.TECHNICIAN);
		orderA = seedOrder(A_PROJECT, technicianAId);
		seedOrder(B_PROJECT, technicianBId);
		seedOrder(UNASSIGNED_PROJECT, null);
		seedOrder(MANAGER_PROJECT, managerId);
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
		for (String email : List.of(MANAGER_EMAIL, TECHNICIAN_A_EMAIL, TECHNICIAN_B_EMAIL, TECHNICIAN_C_EMAIL)) {
			accountRepository.findByEmail(email).ifPresent(accountRepository::delete);
		}
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("PickingVisibilityIntegrationTests Co");
		}
		return company;
	}

	private Long seedAccount(String email, Role role) {
		return companyFixtures.account(company(), email, PASSWORD, role, true).getId();
	}

	private void deactivate(Long accountId) {
		transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE accounts SET active = false WHERE id = ?", accountId));
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/** An OPEN order of its own project, assigned to {@code assigneeId} or unassigned. */
	private Long seedOrder(String projectName, Long assigneeId) {
		return transactionTemplate.execute(status -> {
			Long projectId = jdbcTemplate.queryForObject(
					"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, projectName);
			return jdbcTemplate.queryForObject(
					"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at, assignee_id) "
							+ "VALUES (?, 1, 'NORMAL', ?, ?, ?) RETURNING id",
					Long.class, projectId, LocalDate.now().plusDays(7), Timestamp.from(Instant.now()), assigneeId);
		});
	}

	private ResultActions picking(MockHttpSession session, String assignee) throws Exception {
		var request = get("/picking").session(session);
		if (assignee != null) {
			request = request.param("assignee", assignee);
		}
		return mockMvc.perform(request).andExpect(status().isOk());
	}

	private static void expectOnly(ResultActions result, List<String> listed, List<String> absent)
			throws Exception {
		for (String project : listed) {
			result.andExpect(content().string(containsString(project)));
		}
		for (String project : absent) {
			result.andExpect(content().string(not(containsString(project))));
		}
	}

	// ---- technician ---------------------------------------------------

	@Test
	void technicianSeesOnlyTheirOwnOrdersWithoutFilterOrTechnikColumn() throws Exception {
		MockHttpSession technicianA = loginAs(TECHNICIAN_A_EMAIL);

		ResultActions result = picking(technicianA, null)
			.andExpect(content().string(not(containsString(FILTER_FORM))))
			.andExpect(content().string(not(containsString(TECHNIK_COLUMN))));
		expectOnly(result, List.of(A_PROJECT), List.of(B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT));
	}

	@Test
	void technicianCannotWidenTheirListWithTheAssigneeParam() throws Exception {
		MockHttpSession technicianA = loginAs(TECHNICIAN_A_EMAIL);

		expectOnly(picking(technicianA, technicianBId.toString()), List.of(A_PROJECT),
				List.of(B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT));
		expectOnly(picking(technicianA, "none"), List.of(A_PROJECT),
				List.of(B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT));
	}

	// ---- manager ------------------------------------------------------

	@Test
	void managerWithoutFilterSeesAllFourOrdersWithFilterAndTechnikColumn() throws Exception {
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		ResultActions result = picking(manager, null)
			.andExpect(content().string(containsString(FILTER_FORM)))
			.andExpect(content().string(containsString(TECHNIK_COLUMN)))
			.andExpect(content().string(containsString("<td>" + TECHNICIAN_A_EMAIL + "</td>")))
			.andExpect(content().string(containsString("<td>" + MANAGER_EMAIL + "</td>")))
			.andExpect(content().string(containsString("<td>—</td>")));
		expectOnly(result, List.of(A_PROJECT, B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT), List.of());
	}

	@Test
	void managerFilterByAccountShowsOnlyThatAccountsOrders() throws Exception {
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		ResultActions result = picking(manager, technicianAId.toString())
			.andExpect(content().string(matchesPattern("(?s).*<option value=\"" + technicianAId
					+ "\"\\s+selected=\"selected\">" + TECHNICIAN_A_EMAIL + ".*")));
		expectOnly(result, List.of(A_PROJECT), List.of(B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT));

		expectOnly(picking(manager, managerId.toString()), List.of(MANAGER_PROJECT),
				List.of(A_PROJECT, B_PROJECT, UNASSIGNED_PROJECT));
	}

	/**
	 * Adds a fifth order, assigned to technician C, who is then deactivated:
	 * "Nieprzypisane" covers it together with the order that never had an
	 * assignee, and C drops out of the filter's account entries.
	 */
	@Test
	void managerUnassignedFilterShowsNullAndDeactivatedAssigneesOnly() throws Exception {
		Long technicianCId = seedAccount(TECHNICIAN_C_EMAIL, Role.TECHNICIAN);
		seedOrder(C_PROJECT, technicianCId);
		deactivate(technicianCId);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		ResultActions result = picking(manager, "none")
			.andExpect(content().string(containsString("<option value=\"none\" selected=\"selected\">")))
			.andExpect(content().string(containsString(TECHNICIAN_C_EMAIL + " (nieaktywny)")))
			.andExpect(content().string(not(containsString("value=\"" + technicianCId + "\""))));
		expectOnly(result, List.of(UNASSIGNED_PROJECT, C_PROJECT), List.of(A_PROJECT, B_PROJECT, MANAGER_PROJECT));

		expectOnly(picking(manager, null),
				List.of(A_PROJECT, B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT, C_PROJECT), List.of());
	}

	@Test
	void managerMalformedOrNonAssignableFilterFallsBackToAllOrders() throws Exception {
		Long technicianCId = seedAccount(TECHNICIAN_C_EMAIL, Role.TECHNICIAN);
		deactivate(technicianCId);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		for (String assignee : List.of("abc", "999999999", technicianCId.toString(), "", "99999999999999999999")) {
			expectOnly(picking(manager, assignee), List.of(A_PROJECT, B_PROJECT, UNASSIGNED_PROJECT, MANAGER_PROJECT),
					List.of());
		}
	}

	// ---- handover -----------------------------------------------------

	@Test
	void reassigningTakenOrderMovesItBetweenTechnicianLists() throws Exception {
		Long partId = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES ('Visibility Handover Resistor', 10, true) "
						+ "RETURNING id",
				Long.class));
		Long lineId = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, 5, 5) RETURNING id",
				Long.class, orderA, partId));
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		MockHttpSession technicianA = loginAs(TECHNICIAN_A_EMAIL);
		MockHttpSession technicianB = loginAs(TECHNICIAN_B_EMAIL);

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderA, lineId).session(technicianA)
			.with(csrf())
			.param("quantity", "2"))
			.andExpect(status().is3xxRedirection());

		picking(technicianA, null).andExpect(content().string(containsString(A_PROJECT)));
		picking(technicianB, null).andExpect(content().string(not(containsString(A_PROJECT))));

		mockMvc.perform(post("/orders/{id}/assign", orderA).session(manager)
			.with(csrf())
			.param("assigneeId", technicianBId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderA));

		picking(technicianA, null).andExpect(content().string(not(containsString(A_PROJECT))));
		picking(technicianB, null)
			.andExpect(content().string(containsString(A_PROJECT)))
			.andExpect(content().string(containsString(B_PROJECT)));
	}

}

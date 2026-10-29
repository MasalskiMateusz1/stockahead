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
import org.springframework.web.servlet.ModelAndView;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The S-03 guardrail for orders: company B's manager gets 404 on every
 * {@code /orders/{id}…} route for company A's orders and changes nothing, the
 * order and picking lists and the assignee pickers hold only the user's own
 * company, another company's account is {@code assigneeUnavailable} as an
 * assignee, and two companies' same-named parts never compete for stock.
 * Against real Postgres via Testcontainers, through the real HTTP routes and
 * form login, so the principal carries its company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OrdersCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@orders-isolation.example";

	private static final String TECHNICIAN_A_EMAIL = "technician-a@orders-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@orders-isolation.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@orders-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String PART_NAME = "R1";

	private static final String PROJECT_NAME = "Board X";

	private static final String ASSIGNEE_UNAVAILABLE_ERROR = "Wybrany technik jest nieaktywny lub nie istnieje.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Company companyA;

	private Company companyB;

	private Account managerA;

	private Account technicianA;

	private Account managerB;

	private Account technicianB;

	private Part partA;

	private Part partB;

	private Project projectA;

	private Project projectB;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyA = companyFixtures.company("OrdersCompanyIsolationIntegrationTests A");
		companyB = companyFixtures.company("OrdersCompanyIsolationIntegrationTests B");
		managerA = companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		technicianA = companyFixtures.account(companyA, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		managerB = companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		technicianB = companyFixtures.account(companyB, TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		partA = companyFixtures.part(companyA, PART_NAME, 5, "A1");
		partB = companyFixtures.part(companyB, PART_NAME, 5, "B1");
		projectA = companyFixtures.project(companyA, PROJECT_NAME, true);
		projectB = companyFixtures.project(companyB, PROJECT_NAME, true);
		companyFixtures.bomLine(projectA, partA, 1);
		companyFixtures.bomLine(projectB, partB, 1);
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@orders-isolation.example'");
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/** An untaken {@code OPEN} order of A on A's "R1", assigned to A's technician. */
	private Long seedUntakenOrderA(int reserved) {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id) VALUES (?, ?, 3, 'NORMAL', ?, ?, ?) RETURNING id",
				Long.class, companyA.getId(), projectA.getId(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()), technicianA.getId());
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 3, ?)",
				companyA.getId(), orderId, partA.getId(), reserved);
		return orderId;
	}

	/** A taken {@code OPEN} order of A with a pending completion report by A's technician. */
	private Long seedReportedOrderA(int reserved) {
		Instant now = Instant.now();
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id, taken_at, completion_reported_at, completion_reported_by, built_units) "
						+ "VALUES (?, ?, 2, 'HIGH', ?, ?, ?, ?, ?, ?, 1) RETURNING id",
				Long.class, companyA.getId(), projectA.getId(), LocalDate.now().plusDays(3), Timestamp.from(now),
				technicianA.getId(), Timestamp.from(now), Timestamp.from(now), technicianA.getId());
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 2, ?)",
				companyA.getId(), orderId, partA.getId(), reserved);
		return orderId;
	}

	/** An untaken, unassigned {@code OPEN} order of B on B's "R1". */
	private Long seedOrderB() {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, 1, 'NORMAL', ?, ?) RETURNING id",
				Long.class, companyB.getId(), projectB.getId(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()));
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 1, 1)",
				companyB.getId(), orderId, partB.getId());
		return orderId;
	}

	/** Everything a manager route could change on an order, plus its lines' reservations. */
	private Map<String, Object> snapshotOf(Long orderId) {
		Map<String, Object> snapshot = jdbcTemplate.queryForMap(
				"SELECT status, priority, required_date, assignee_id, taken_at, completion_reported_at, "
						+ "completion_reported_by, built_units, completed_at, cancelled_at FROM orders WHERE id = ?",
				orderId);
		snapshot.put("reserved", jdbcTemplate.queryForList(
				"SELECT reserved_quantity FROM order_lines WHERE order_id = ? ORDER BY id", Integer.class, orderId));
		return snapshot;
	}

	private Long assigneeIdOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT assignee_id FROM orders WHERE id = ?", Long.class, orderId);
	}

	private int orderCountOf(Company company) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE company_id = ?",
				Integer.class, company.getId());
		return count == null ? 0 : count;
	}

	private int reservedOf(Part part) {
		Integer reserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(ol.reserved_quantity), 0) FROM order_lines ol JOIN orders o ON o.id = ol.order_id "
						+ "WHERE ol.part_id = ? AND o.status = 'OPEN'",
				Integer.class, part.getId());
		return reserved == null ? 0 : reserved;
	}

	private Long orderIdOf(Company company) {
		return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE company_id = ?", Long.class,
				company.getId());
	}

	private ModelAndView getModel(String url, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(url).session(session))
			.andExpect(status().isOk())
			.andReturn().getModelAndView();
	}

	@SuppressWarnings("unchecked")
	private static List<Long> orderIdsIn(ModelAndView modelAndView, String attribute) {
		return ((List<Order>) modelAndView.getModel().get(attribute)).stream().map(Order::getId).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<Long> accountIdsIn(ModelAndView modelAndView) {
		return ((List<Account>) modelAndView.getModel().get("assignableAccounts")).stream()
			.map(Account::getId)
			.toList();
	}

	// ---- by-id routes -------------------------------------------------

	@Test
	void otherCompanysManagerGets404OnOrderReadRoutes() throws Exception {
		Long orderA = seedUntakenOrderA(3);
		MockHttpSession session = loginAs(MANAGER_B_EMAIL);

		mockMvc.perform(get("/orders/{id}", orderA).session(session))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/orders/{id}/cancel", orderA).session(session))
			.andExpect(status().isNotFound());
	}

	@Test
	void otherCompanysManagerGets404OnOrderWriteRoutesAndChangesNothing() throws Exception {
		Long reportedA = seedReportedOrderA(2);
		Long untakenA = seedUntakenOrderA(3);
		Map<String, Object> reportedBefore = snapshotOf(reportedA);
		Map<String, Object> untakenBefore = snapshotOf(untakenA);
		MockHttpSession session = loginAs(MANAGER_B_EMAIL);

		mockMvc.perform(post("/orders/{id}/confirm-completion", reportedA).session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/orders/{id}/reject-completion", reportedA).session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/orders/{id}/change", untakenA).session(session).with(csrf())
				.param("priority", "HIGH")
				.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/orders/{id}/assign", untakenA).session(session).with(csrf())
				.param("assigneeId", ""))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/orders/{id}/cancel", untakenA).session(session).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(snapshotOf(reportedA)).isEqualTo(reportedBefore);
		assertThat(snapshotOf(untakenA)).isEqualTo(untakenBefore);
	}

	// ---- lists --------------------------------------------------------

	@Test
	void orderAndPickingListsShowOnlyTheUsersOwnCompany() throws Exception {
		Long reportedA = seedReportedOrderA(2);
		Long untakenA = seedUntakenOrderA(3);
		Long orderB = seedOrderB();
		jdbcTemplate.update("UPDATE orders SET assignee_id = ? WHERE id = ?", technicianB.getId(), orderB);
		MockHttpSession manager = loginAs(MANAGER_B_EMAIL);

		ModelAndView orders = getModel("/orders", manager);
		assertThat(orderIdsIn(orders, "orders")).containsExactly(orderB);
		assertThat(orderIdsIn(orders, "pendingOrders")).isEmpty();

		ModelAndView managerPicking = getModel("/picking", manager);
		assertThat(orderIdsIn(managerPicking, "orders")).containsExactly(orderB)
			.doesNotContain(reportedA, untakenA);

		ModelAndView technicianPicking = getModel("/picking", loginAs(TECHNICIAN_B_EMAIL));
		assertThat(orderIdsIn(technicianPicking, "orders")).containsExactly(orderB);
	}

	// ---- assignee -----------------------------------------------------

	@Test
	void assigneePickersOfferOnlyTheUsersOwnCompanysAccounts() throws Exception {
		Long orderB = seedOrderB();
		MockHttpSession session = loginAs(MANAGER_B_EMAIL);

		assertThat(accountIdsIn(getModel("/orders/new", session)))
			.containsExactlyInAnyOrder(managerB.getId(), technicianB.getId());
		assertThat(accountIdsIn(getModel("/orders/" + orderB, session)))
			.containsExactlyInAnyOrder(managerB.getId(), technicianB.getId());
	}

	@Test
	void anotherCompanysAccountAsAssigneeIsUnavailableAndWritesNothing() throws Exception {
		Long orderB = seedOrderB();
		MockHttpSession session = loginAs(MANAGER_B_EMAIL);

		mockMvc.perform(post("/orders").session(session).with(csrf())
				.param("projectId", projectB.getId().toString())
				.param("quantityUnits", "1")
				.param("priority", "NORMAL")
				.param("requiredDate", LocalDate.now().plusDays(1).toString())
				.param("assigneeId", technicianA.getId().toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ASSIGNEE_UNAVAILABLE_ERROR)));
		assertThat(orderCountOf(companyB)).isEqualTo(1);

		mockMvc.perform(post("/orders/{id}/assign", orderB).session(session).with(csrf())
				.param("assigneeId", technicianA.getId().toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ASSIGNEE_UNAVAILABLE_ERROR)));
		assertThat(assigneeIdOf(orderB)).isNull();
	}

	// ---- allocation ---------------------------------------------------

	@Test
	void sameNamedPartsOfTwoCompaniesNeverCompeteForStock() throws Exception {
		String requiredDate = LocalDate.now().plusDays(1).toString();
		mockMvc.perform(post("/orders").session(loginAs(MANAGER_A_EMAIL)).with(csrf())
				.param("projectId", projectA.getId().toString())
				.param("quantityUnits", "5")
				.param("priority", "NORMAL")
				.param("requiredDate", requiredDate))
			.andExpect(status().is3xxRedirection());
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		mockMvc.perform(post("/orders").session(sessionB).with(csrf())
				.param("projectId", projectB.getId().toString())
				.param("quantityUnits", "5")
				.param("priority", "HIGH")
				.param("requiredDate", requiredDate))
			.andExpect(status().is3xxRedirection());

		assertThat(reservedOf(partA)).isEqualTo(5);
		assertThat(reservedOf(partB)).isEqualTo(5);

		mockMvc.perform(post("/orders/{id}/cancel", orderIdOf(companyB)).session(sessionB).with(csrf()))
			.andExpect(status().is3xxRedirection());

		assertThat(reservedOf(partB)).isZero();
		assertThat(reservedOf(partA)).isEqualTo(5);
	}

}

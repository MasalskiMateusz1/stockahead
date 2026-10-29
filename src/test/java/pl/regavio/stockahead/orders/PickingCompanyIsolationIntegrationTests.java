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

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The company guardrail for picking: company B's manager and technician get
 * 404 on every {@code /picking/{id}…} route for company A's orders and lines
 * and change nothing, a foreign line under B's own order id is 404 too, B's
 * own pick and report still work, and the list's "to pick" totals read only
 * one company's lines. Against real Postgres via Testcontainers, through the
 * real HTTP routes and form login, so the principal carries its company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PickingCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@picking-isolation.example";

	private static final String TECHNICIAN_A_EMAIL = "technician-a@picking-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@picking-isolation.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@picking-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String PART_NAME = "R1";

	private static final String PROJECT_NAME = "Board X";

	private static final List<String> OTHER_COMPANY_USERS = List.of(MANAGER_B_EMAIL, TECHNICIAN_B_EMAIL);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderLineRepository orderLineRepository;

	private Company companyA;

	private Company companyB;

	private Account technicianA;

	private Account technicianB;

	private Part partA;

	private Part partB;

	private Project projectA;

	private Project projectB;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyA = companyFixtures.company("PickingCompanyIsolationIntegrationTests A");
		companyB = companyFixtures.company("PickingCompanyIsolationIntegrationTests B");
		companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		technicianA = companyFixtures.account(companyA, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@picking-isolation.example'");
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/**
	 * An {@code OPEN} order of A on A's "R1" (3 units, 3 reserved), assigned to
	 * A's technician; already taken when {@code taken} is set.
	 */
	private Long seedOrderA(boolean taken) {
		Instant now = Instant.now();
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id, taken_at) VALUES (?, ?, 3, 'NORMAL', ?, ?, ?, ?) RETURNING id",
				Long.class, companyA.getId(), projectA.getId(), LocalDate.now().plusDays(7), Timestamp.from(now),
				technicianA.getId(), taken ? Timestamp.from(now) : null);
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 3, 3)",
				companyA.getId(), orderId, partA.getId());
		return orderId;
	}

	/** An untaken {@code OPEN} order of B on B's "R1" (1 unit, 1 reserved), assigned to B's technician. */
	private Long seedOrderB() {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id) VALUES (?, ?, 1, 'NORMAL', ?, ?, ?) RETURNING id",
				Long.class, companyB.getId(), projectB.getId(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()), technicianB.getId());
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 1, 1)",
				companyB.getId(), orderId, partB.getId());
		return orderId;
	}

	private Long lineIdOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, orderId);
	}

	/**
	 * Everything a picking route could change on an order: its take and
	 * report fields, its lines' reserved/picked quantities, and the stock of
	 * the parts those lines touch.
	 */
	private Map<String, Object> snapshotOf(Long orderId) {
		Map<String, Object> snapshot = jdbcTemplate.queryForMap(
				"SELECT taken_at, completion_reported_at, completion_reported_by, built_units FROM orders WHERE id = ?",
				orderId);
		snapshot.put("lines", jdbcTemplate.queryForList(
				"SELECT reserved_quantity, picked_quantity FROM order_lines WHERE order_id = ? ORDER BY id", orderId));
		snapshot.put("stock", jdbcTemplate.queryForList(
				"SELECT p.quantity FROM parts p JOIN order_lines ol ON ol.part_id = p.id WHERE ol.order_id = ? "
						+ "ORDER BY ol.id",
				Integer.class, orderId));
		return snapshot;
	}

	private int quantityOf(Part part) {
		Integer quantity = jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class,
				part.getId());
		return quantity == null ? 0 : quantity;
	}

	// ---- by-id routes -------------------------------------------------

	@Test
	void otherCompanysUsersGet404OnPickingDetail() throws Exception {
		Long orderA = seedOrderA(false);

		for (String email : OTHER_COMPANY_USERS) {
			mockMvc.perform(get("/picking/{id}", orderA).session(loginAs(email)))
				.andExpect(status().isNotFound());
		}
	}

	@Test
	void otherCompanysUsersGet404OnPickAndChangeNothing() throws Exception {
		Long orderA = seedOrderA(false);
		Long lineA = lineIdOf(orderA);
		Map<String, Object> before = snapshotOf(orderA);

		for (String email : OTHER_COMPANY_USERS) {
			MockHttpSession session = loginAs(email);
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderA, lineA).session(session)
					.with(csrf())
					.param("quantity", "1"))
				.andExpect(status().isNotFound());
			// The scoped lookup runs before validation: a bad quantity is still 404, not a re-render.
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderA, lineA).session(session)
					.with(csrf())
					.param("quantity", "abc"))
				.andExpect(status().isNotFound());
		}

		assertThat(snapshotOf(orderA)).isEqualTo(before);
		assertThat(quantityOf(partA)).isEqualTo(5);
	}

	@Test
	void otherCompanysUsersGet404OnReportCompletionAndChangeNothing() throws Exception {
		Long orderA = seedOrderA(true);
		Map<String, Object> before = snapshotOf(orderA);

		for (String email : OTHER_COMPANY_USERS) {
			mockMvc.perform(post("/picking/{orderId}/report-completion", orderA).session(loginAs(email))
					.with(csrf())
					.param("builtUnits", "1"))
				.andExpect(status().isNotFound());
		}

		Map<String, Object> after = snapshotOf(orderA);
		assertThat(after).isEqualTo(before);
		assertThat(after.get("completion_reported_at")).isNull();
		assertThat(after.get("completion_reported_by")).isNull();
		assertThat(after.get("built_units")).isNull();
	}

	@Test
	void foreignLineUnderOwnOrderIdIs404() throws Exception {
		Long orderA = seedOrderA(false);
		Long lineA = lineIdOf(orderA);
		Long orderB = seedOrderB();
		Map<String, Object> beforeA = snapshotOf(orderA);
		Map<String, Object> beforeB = snapshotOf(orderB);

		for (String email : OTHER_COMPANY_USERS) {
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderB, lineA).session(loginAs(email))
					.with(csrf())
					.param("quantity", "1"))
				.andExpect(status().isNotFound());
		}

		assertThat(snapshotOf(orderA)).isEqualTo(beforeA);
		assertThat(snapshotOf(orderB)).isEqualTo(beforeB);
		assertThat(quantityOf(partA)).isEqualTo(5);
		assertThat(quantityOf(partB)).isEqualTo(5);
	}

	@Test
	void ownCompanyPickAndReportStillWork() throws Exception {
		Long orderB = seedOrderB();
		Long lineB = lineIdOf(orderB);
		MockHttpSession session = loginAs(TECHNICIAN_B_EMAIL);

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderB, lineB).session(session)
				.with(csrf())
				.param("quantity", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/picking/" + orderB));

		assertThat(quantityOf(partB)).isEqualTo(4);
		Map<String, Object> line = jdbcTemplate.queryForMap(
				"SELECT reserved_quantity, picked_quantity FROM order_lines WHERE id = ?", lineB);
		assertThat(line.get("reserved_quantity")).isEqualTo(0);
		assertThat(line.get("picked_quantity")).isEqualTo(1);

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderB).session(session)
				.with(csrf())
				.param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/picking/" + orderB));

		Map<String, Object> order = jdbcTemplate.queryForMap(
				"SELECT taken_at, completion_reported_at, completion_reported_by, built_units FROM orders WHERE id = ?",
				orderB);
		assertThat(order.get("taken_at")).isNotNull();
		assertThat(order.get("completion_reported_at")).isNotNull();
		assertThat(order.get("completion_reported_by")).isEqualTo(technicianB.getId());
		assertThat(order.get("built_units")).isEqualTo(1);
		assertThat(quantityOf(partA)).isEqualTo(5);
	}

	// ---- list totals --------------------------------------------------

	@Test
	void toPickTotalsReadOnlyTheGivenCompany() {
		Long orderA = seedOrderA(false);
		Long orderB = seedOrderB();

		List<Long> orderIds = orderLineRepository.reservedQuantitiesByOpenOrder(companyB.getId())
			.stream()
			.map(row -> (Long) row[0])
			.toList();

		assertThat(orderIds).contains(orderB).doesNotContain(orderA);
	}

}

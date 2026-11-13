package pl.regavio.stockahead.account;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard's count cards: a manager sees the company's orders awaiting
 * confirmation, its open orders and the parts on its shopping list; a
 * technician sees only the open orders assigned to them. Counts are scoped to
 * the principal's company, so another company's orders and shortages never
 * change them, and a company with no orders sees zeros. The operator is still
 * redirected to the company list. Against real Postgres via Testcontainers,
 * through the real HTTP route and form login, so the principal carries its
 * company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class DashboardCountsIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@dashboard-counts.example";

	private static final String TECHNICIAN_A_EMAIL = "technician-a@dashboard-counts.example";

	private static final String OTHER_TECHNICIAN_A_EMAIL = "technician-a2@dashboard-counts.example";

	private static final String MANAGER_B_EMAIL = "manager-b@dashboard-counts.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@dashboard-counts.example";

	private static final String OPERATOR_EMAIL = "operator@dashboard-counts.example";

	private static final String PASSWORD = "correct-password";

	private static final String PENDING_LABEL = "Do potwierdzenia";

	private static final String OPEN_LABEL = "Otwarte zlecenia";

	private static final String SHORTAGE_LABEL = "Braki do zakupu";

	private static final String MY_PICKING_LABEL = "Moje zlecenia do kompletacji";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Company companyA;

	private Company companyB;

	private Account technicianA;

	private Account otherTechnicianA;

	private Account technicianB;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyA = companyFixtures.company("DashboardCountsIntegrationTests A");
		companyB = companyFixtures.company("DashboardCountsIntegrationTests B");
		companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		technicianA = companyFixtures.account(companyA, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		otherTechnicianA = companyFixtures.account(companyA, OTHER_TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN,
				true);
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		technicianB = companyFixtures.account(companyB, TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN, true);
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@dashboard-counts.example'");
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private String dashboard(MockHttpSession session) throws Exception {
		return mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** Seeds an OPEN order of {@code project}, not taken, assigned to {@code assignee} (may be null). */
	private Long seedOpenOrder(Company company, Project project, Account assignee) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id) VALUES (?, ?, 1, 'NORMAL', ?, ?, ?) RETURNING id",
				Long.class, company.getId(), project.getId(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()), assignee == null ? null : assignee.getId());
	}

	/** Seeds an OPEN order of {@code project}, taken and reported complete by {@code assignee}. */
	private Long seedReportedOrder(Company company, Project project, Account assignee) {
		Timestamp now = Timestamp.from(Instant.now());
		return jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "assignee_id, taken_at, completion_reported_at, completion_reported_by) "
						+ "VALUES (?, ?, 1, 'NORMAL', ?, ?, ?, ?, ?, ?) RETURNING id",
				Long.class, company.getId(), project.getId(), LocalDate.now().plusDays(7), now, assignee.getId(),
				now, now, assignee.getId());
	}

	/** Seeds a COMPLETED order of {@code project}, reported by and assigned to {@code assignee}. */
	private Long seedCompletedOrder(Company company, Project project, Account assignee) {
		Timestamp now = Timestamp.from(Instant.now());
		return jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at, "
						+ "status, assignee_id, taken_at, completion_reported_at, completion_reported_by, completed_at) "
						+ "VALUES (?, ?, 1, 'NORMAL', ?, ?, 'COMPLETED', ?, ?, ?, ?, ?) RETURNING id",
				Long.class, company.getId(), project.getId(), LocalDate.now().plusDays(7), now, assignee.getId(),
				now, now, assignee.getId(), now);
	}

	private void seedLine(Company company, Long orderId, Part part, int required, int reserved) {
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?, ?)",
				company.getId(), orderId, part.getId(), required, reserved);
	}

	/**
	 * Company A: one reported order and three unreported open orders (two of
	 * them short, on two distinct parts, one part short on two orders), plus
	 * a completed order whose short line must not count. The technician of A
	 * holds one unreported and the reported order.
	 */
	private void seedCompanyA() {
		Part resistor = companyFixtures.part(companyA, "Resistor 10k", 2);
		Part capacitor = companyFixtures.part(companyA, "Capacitor 100nF", 0);
		Part diode = companyFixtures.part(companyA, "Diode 1N4148", 5);
		Project project = companyFixtures.project(companyA, "Alpha Board", true);

		Long mine = seedOpenOrder(companyA, project, technicianA);
		seedLine(companyA, mine, resistor, 7, 2);
		Long unassigned = seedOpenOrder(companyA, project, null);
		seedLine(companyA, unassigned, resistor, 3, 0);
		seedLine(companyA, unassigned, capacitor, 4, 0);
		Long notShort = seedOpenOrder(companyA, project, otherTechnicianA);
		seedLine(companyA, notShort, diode, 2, 2);
		seedReportedOrder(companyA, project, technicianA);
		Long completed = seedCompletedOrder(companyA, project, technicianA);
		seedLine(companyA, completed, diode, 9, 0);
	}

	/** Company B: four unreported open orders of its technician, one part short on one of them. */
	private void seedCompanyB() {
		Part inductor = companyFixtures.part(companyB, "Inductor 10uH", 0);
		Project project = companyFixtures.project(companyB, "Bravo Board", true);
		Long shortOrder = seedOpenOrder(companyB, project, technicianB);
		seedLine(companyB, shortOrder, inductor, 6, 0);
		for (int i = 0; i < 3; i++) {
			seedOpenOrder(companyB, project, technicianB);
		}
	}

	/** The number shown on the count card labelled {@code label}, or null if the card is absent. */
	private static Integer count(String html, String label) {
		Matcher matcher = Pattern
			.compile("<span class=\"kpi-label\">" + Pattern.quote(label)
					+ "</span>\\s*<span class=\"kpi-value\">(\\d+)</span>")
			.matcher(html);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	// ---- manager -------------------------------------------------------

	@Test
	void managerSeesOwnCompanyCountsWhileOtherCompanyHasDifferentData() throws Exception {
		seedCompanyA();
		seedCompanyB();

		String pageA = dashboard(loginAs(MANAGER_A_EMAIL));
		assertThat(count(pageA, PENDING_LABEL)).isEqualTo(1);
		assertThat(count(pageA, OPEN_LABEL)).isEqualTo(3);
		assertThat(count(pageA, SHORTAGE_LABEL)).isEqualTo(2);
		assertThat(count(pageA, MY_PICKING_LABEL)).isNull();
		assertThat(pageA).contains("href=\"/orders\"")
			.contains("href=\"/purchasing\"")
			.contains("class=\"kpi kpi-warning\"");

		String pageB = dashboard(loginAs(MANAGER_B_EMAIL));
		assertThat(count(pageB, PENDING_LABEL)).isEqualTo(0);
		assertThat(count(pageB, OPEN_LABEL)).isEqualTo(4);
		assertThat(count(pageB, SHORTAGE_LABEL)).isEqualTo(1);
	}

	@Test
	void companyWithNoOrdersShowsZeros() throws Exception {
		seedCompanyA();

		String pageB = dashboard(loginAs(MANAGER_B_EMAIL));
		assertThat(count(pageB, PENDING_LABEL)).isEqualTo(0);
		assertThat(count(pageB, OPEN_LABEL)).isEqualTo(0);
		assertThat(count(pageB, SHORTAGE_LABEL)).isEqualTo(0);
		assertThat(pageB).doesNotContain("kpi-warning");

		String technicianPageB = dashboard(loginAs(TECHNICIAN_B_EMAIL));
		assertThat(count(technicianPageB, MY_PICKING_LABEL)).isEqualTo(0);
	}

	// ---- technician ----------------------------------------------------

	@Test
	void technicianSeesOnlyOwnOpenOrderCountAndNoManagerCards() throws Exception {
		seedCompanyA();
		seedCompanyB();

		String page = dashboard(loginAs(TECHNICIAN_A_EMAIL));
		assertThat(count(page, MY_PICKING_LABEL)).isEqualTo(2);
		assertThat(count(page, PENDING_LABEL)).isNull();
		assertThat(count(page, OPEN_LABEL)).isNull();
		assertThat(count(page, SHORTAGE_LABEL)).isNull();
		assertThat(page).contains("href=\"/picking\"")
			.doesNotContain(PENDING_LABEL)
			.doesNotContain(OPEN_LABEL)
			.doesNotContain(SHORTAGE_LABEL)
			.doesNotContain("href=\"/orders\"")
			.doesNotContain("href=\"/purchasing\"");

		String otherPage = dashboard(loginAs(OTHER_TECHNICIAN_A_EMAIL));
		assertThat(count(otherPage, MY_PICKING_LABEL)).isEqualTo(1);

		String pageB = dashboard(loginAs(TECHNICIAN_B_EMAIL));
		assertThat(count(pageB, MY_PICKING_LABEL)).isEqualTo(4);
	}

	// ---- operator ------------------------------------------------------

	@Test
	void operatorIsStillRedirectedToCompanyList() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);

		mockMvc.perform(get("/").session(loginAs(OPERATOR_EMAIL)))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/operator/companies"));
	}

	// ---- no ping tile --------------------------------------------------

	@Test
	void managerDashboardHasNoPingTile() throws Exception {
		assertThat(dashboard(loginAs(MANAGER_A_EMAIL))).doesNotContain("/manager/ping");
	}

}

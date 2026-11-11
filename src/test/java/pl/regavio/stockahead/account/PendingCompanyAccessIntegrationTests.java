package pl.regavio.stockahead.account;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pending-company gate: a user of a company that is not active logs in
 * but reaches only {@code /awaiting-approval}. {@code /} and at least one
 * route of every warehouse controller redirect there without changing any
 * data; the awaiting page renders for the pending user, sends an
 * active-company user to {@code /} and answers the operator with 403; the
 * stylesheet stays reachable; and once the company becomes active the same
 * session reaches the dashboard.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PendingCompanyAccessIntegrationTests {

	private static final String PASSWORD = "correct-password";

	private static final String MANAGER_EMAIL = "manager@pending-access.example";

	private static final String TECHNICIAN_EMAIL = "technician@pending-access.example";

	private static final String ACTIVE_MANAGER_EMAIL = "active-manager@pending-access.example";

	private static final String OPERATOR_EMAIL = "operator@pending-access.example";

	private static final String AWAITING_MESSAGE = "Twoja firma oczekuje na zatwierdzenie przez operatora.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM stock_corrections WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE email LIKE '%@pending-access.example')");
		companyFixtures.cleanUp();
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();
	}

	/** A pending company with a manager, a part, a project with one BOM line, no orders. */
	private record Warehouse(Company company, Account manager, Part part, Project project) {
	}

	private Warehouse pendingWarehouse() {
		Company company = companyFixtures.company("PendingAccess Warehouse Co", CompanyStatus.PENDING);
		Account manager = companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		Part part = companyFixtures.part(company, "PendingAccess Resistor", 10, "A-1");
		Part otherPart = companyFixtures.part(company, "PendingAccess Capacitor", 4, "A-2");
		Project project = companyFixtures.project(company, "PendingAccess Project", true);
		companyFixtures.bomLine(project, otherPart, 2);
		return new Warehouse(company, manager, part, project);
	}

	private void expectAwaiting(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
		mockMvc.perform(request.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
	}

	private int partQuantity(Part part) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part.getId());
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	// ---------- Home and the awaiting page ----------

	@Test
	void homeRedirectsPendingManagerToAwaitingApproval() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/"));
	}

	@Test
	void rejectedAndBlockedCompaniesAreGatedToo() throws Exception {
		Company company = companyFixtures.company("PendingAccess Rejected Co", CompanyStatus.REJECTED);
		companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		expectAwaiting(manager, get("/"));

		jdbcTemplate.update("UPDATE companies SET status = 'BLOCKED' WHERE id = ?", company.getId());
		expectAwaiting(manager, get("/parts"));
	}

	@Test
	void awaitingPageRendersForPendingManagerWithoutWarehouseNavigation() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		String html = mockMvc.perform(get("/awaiting-approval").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(AWAITING_MESSAGE)))
			.andExpect(content().string(containsString(MANAGER_EMAIL)))
			.andExpect(content().string(containsString("action=\"/logout\"")))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).doesNotContain("class=\"topbar\"")
			.doesNotContain("href=\"/parts\"")
			.doesNotContain("href=\"/manager/accounts\"");
	}

	@Test
	void awaitingPageRendersForPendingTechnician() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		companyFixtures.account(warehouse.company(), TECHNICIAN_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		MockHttpSession technician = loginAs(TECHNICIAN_EMAIL);

		expectAwaiting(technician, get("/picking"));
		mockMvc.perform(get("/awaiting-approval").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(AWAITING_MESSAGE)));
	}

	@Test
	void awaitingPageRedirectsActiveCompanyManagerToHome() throws Exception {
		Company company = companyFixtures.company("PendingAccess Active Co");
		companyFixtures.account(company, ACTIVE_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession manager = loginAs(ACTIVE_MANAGER_EMAIL);

		mockMvc.perform(get("/awaiting-approval").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"));
	}

	@Test
	void operatorGets403OnAwaitingPage() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);
		MockHttpSession operator = loginAs(OPERATOR_EMAIL);

		mockMvc.perform(get("/awaiting-approval").session(operator))
			.andExpect(status().isForbidden());
	}

	@Test
	void stylesheetIsReachableForPendingManager() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/css/app.css").session(manager))
			.andExpect(status().isOk());
	}

	@Test
	void sameSessionReachesDashboardOnceCompanyIsActive() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		expectAwaiting(manager, get("/"));

		jdbcTemplate.update("UPDATE companies SET status = 'ACTIVE' WHERE id = ?", warehouse.company().getId());

		mockMvc.perform(get("/").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/parts\"")));
	}

	@Test
	void logoutWorksForPendingManager() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/logout").with(csrf()).session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?logout"));
	}

	// ---------- Pending manager → awaiting page on every warehouse controller ----------

	@Test
	void pendingManagerIsGatedFromPartControllerAndCreatesNothing() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/parts"));
		expectAwaiting(manager, post("/parts").with(csrf())
				.param("name", "PendingAccess New Part")
				.param("quantity", "5")
				.param("locations", "B-1"));
		expectAwaiting(manager, post("/parts/{id}", warehouse.part().getId()).with(csrf())
				.param("name", "PendingAccess Renamed")
				.param("locations", "B-1"));

		assertThat(count("SELECT count(*) FROM parts WHERE company_id = ?", warehouse.company().getId())).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM parts WHERE id = ?", String.class,
				warehouse.part().getId())).isEqualTo("PendingAccess Resistor");
	}

	@Test
	void pendingManagerIsGatedFromPartImportController() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/parts/import"));
		expectAwaiting(manager, post("/parts/import/confirm").with(csrf()).param("token", "any-token"));

		assertThat(count("SELECT count(*) FROM parts WHERE company_id = ?", warehouse.company().getId())).isEqualTo(2);
	}

	@Test
	void pendingManagerIsGatedFromDeliveryControllerAndStockIsUnchanged() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/deliveries/new"));
		expectAwaiting(manager, post("/deliveries").with(csrf())
				.param("rows", "5")
				.param("partId0", warehouse.part().getId().toString())
				.param("quantity0", "7"));

		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
	}

	@Test
	void pendingManagerIsGatedFromStockCorrectionControllerAndStockIsUnchanged() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/parts/{id}/correction", warehouse.part().getId()));
		expectAwaiting(manager, post("/parts/{id}/correction/adjust", warehouse.part().getId()).with(csrf())
				.param("delta", "-3")
				.param("reason", "pending attempt"));

		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
		assertThat(count("SELECT count(*) FROM stock_corrections WHERE part_id = ?", warehouse.part().getId()))
			.isZero();
	}

	@Test
	void pendingManagerIsGatedFromProjectControllerAndProjectIsUnchanged() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/projects"));
		expectAwaiting(manager, get("/projects/{id}", warehouse.project().getId()));
		expectAwaiting(manager, post("/projects/{id}", warehouse.project().getId()).with(csrf())
				.param("name", "PendingAccess Renamed Project"));

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM projects WHERE id = ?", String.class,
				warehouse.project().getId())).isEqualTo("PendingAccess Project");
	}

	@Test
	void pendingManagerIsGatedFromProjectBomControllerAndBomIsUnchanged() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, post("/projects/{id}/bom", warehouse.project().getId()).with(csrf())
				.param("partId", warehouse.part().getId().toString())
				.param("quantityPerUnit", "3"));

		assertThat(count("SELECT count(*) FROM bom_lines WHERE project_id = ?", warehouse.project().getId()))
			.isEqualTo(1);
	}

	@Test
	void pendingManagerIsGatedFromProjectLinkControllerAndNoLinkIsAdded() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, post("/projects/{id}/links", warehouse.project().getId()).with(csrf())
				.param("url", "https://example.com/datasheet")
				.param("label", "Datasheet"));

		assertThat(count("SELECT count(*) FROM project_links WHERE project_id = ?", warehouse.project().getId()))
			.isZero();
	}

	@Test
	void pendingManagerIsGatedFromOrderControllerAndNoOrderIsCreated() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/orders"));
		expectAwaiting(manager, post("/orders").with(csrf())
				.param("projectId", warehouse.project().getId().toString())
				.param("quantityUnits", "1")
				.param("priority", "NORMAL")
				.param("requiredDate", "2099-01-01"));

		assertThat(count("SELECT count(*) FROM orders WHERE company_id = ?", warehouse.company().getId())).isZero();
		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
	}

	@Test
	void pendingManagerIsGatedFromPickingController() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/picking"));
	}

	@Test
	void pendingManagerIsGatedFromShoppingListController() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/purchasing"));
		expectAwaiting(manager, get("/purchasing/export"));
	}

	@Test
	void pendingManagerIsGatedFromCompanyAccountControllerAndAccountsAreUnchanged() throws Exception {
		Warehouse warehouse = pendingWarehouse();
		Account technician = companyFixtures.account(warehouse.company(), TECHNICIAN_EMAIL, PASSWORD,
				Role.TECHNICIAN, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/manager/accounts"));
		expectAwaiting(manager, post("/manager/accounts/{id}/deactivate", technician.getId()).with(csrf()));
		expectAwaiting(manager, post("/manager/accounts").with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "created-while-pending@pending-access.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD));

		assertThat(jdbcTemplate.queryForObject("SELECT active FROM accounts WHERE id = ?", Boolean.class,
				technician.getId())).isTrue();
		assertThat(count("SELECT count(*) FROM accounts WHERE email = ?",
				"created-while-pending@pending-access.example")).isZero();
	}

	@Test
	void pendingManagerIsGatedFromManagerPingController() throws Exception {
		pendingWarehouse();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		expectAwaiting(manager, get("/manager/ping"));
	}

}

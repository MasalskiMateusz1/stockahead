package pl.regavio.stockahead.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator's company list and the guardrail around it: the operator sees
 * only the list, gets 403 on at least one route of every warehouse
 * controller without changing any data, and managers and technicians get 403
 * on {@code /operator/companies}.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OperatorAccessIntegrationTests {

	private static final String PASSWORD = "correct-password";

	private static final String OPERATOR_EMAIL = "operator@operator-access.example";

	private static final String FIRST_MANAGER_EMAIL = "first-manager@operator-access.example";

	private static final String SECOND_MANAGER_EMAIL = "second-manager@operator-access.example";

	private static final String TECHNICIAN_A_EMAIL = "technician-a@operator-access.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@operator-access.example";

	private static final String EMPTY_STATE = "Brak zarejestrowanych firm.";

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
				+ "(SELECT id FROM accounts WHERE email LIKE '%@operator-access.example')");
		companyFixtures.cleanUp();
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();
	}

	private MockHttpSession loginOperator() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);
		return loginAs(OPERATOR_EMAIL);
	}

	private void setCompany(Company company, Instant createdAt, CompanyStatus status) {
		jdbcTemplate.update("UPDATE companies SET created_at = ?, status = ? WHERE id = ?",
				Timestamp.from(createdAt), status.name(), company.getId());
	}

	private void setAccountCreatedAt(Account account, Instant createdAt) {
		jdbcTemplate.update("UPDATE accounts SET created_at = ? WHERE id = ?", Timestamp.from(createdAt),
				account.getId());
	}

	/** The body row ({@code <tr>…</tr>}) of the company list that contains {@code companyName}. */
	private static String rowOf(String html, String companyName) {
		return Arrays.stream(html.split("<tr>"))
			.filter(row -> row.contains("<td>" + companyName + "</td>"))
			.map(row -> row.substring(0, row.indexOf("</tr>")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no row for " + companyName));
	}

	// ---------- Company list ----------

	@Test
	void listShowsEveryCompanyNewestFirstWithFirstCreatedManagerAndStatusLabel() throws Exception {
		Company companyA = companyFixtures.company("OperatorAccess Company A");
		Company companyB = companyFixtures.company("OperatorAccess Company B");
		Company companyC = companyFixtures.company("OperatorAccess Company C");
		Company companyD = companyFixtures.company("OperatorAccess Company D");
		setCompany(companyA, Instant.parse("2026-01-10T10:00:00Z"), CompanyStatus.ACTIVE);
		setCompany(companyB, Instant.parse("2026-03-05T10:00:00Z"), CompanyStatus.PENDING);
		setCompany(companyC, Instant.parse("2026-02-01T10:00:00Z"), CompanyStatus.REJECTED);
		setCompany(companyD, Instant.parse("2026-02-20T10:00:00Z"), CompanyStatus.BLOCKED);

		// Company A: a technician created earliest, then two managers. The
		// deactivated manager has the higher id but the earlier created_at,
		// so created_at — not id, role or active flag — decides.
		Account technicianA = companyFixtures.account(companyA, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		Account secondManager = companyFixtures.account(companyA, SECOND_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		Account firstManager = companyFixtures.account(companyA, FIRST_MANAGER_EMAIL, PASSWORD, Role.MANAGER, false);
		setAccountCreatedAt(technicianA, Instant.parse("2026-01-10T10:00:00Z"));
		setAccountCreatedAt(firstManager, Instant.parse("2026-01-10T10:05:00Z"));
		setAccountCreatedAt(secondManager, Instant.parse("2026-01-11T10:00:00Z"));
		// Company B: only a technician, so no manager email.
		companyFixtures.account(companyB, TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN, true);

		MockHttpSession operator = loginOperator();
		String html = mockMvc.perform(get("/operator/companies").session(operator))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<th>Nazwa</th><th>E-mail kierownika</th>"
					+ "<th>Data rejestracji</th><th>Status</th>")))
			.andExpect(content().string(not(containsString(EMPTY_STATE))))
			.andReturn().getResponse().getContentAsString();

		int b = html.indexOf("<td>OperatorAccess Company B</td>");
		int d = html.indexOf("<td>OperatorAccess Company D</td>");
		int c = html.indexOf("<td>OperatorAccess Company C</td>");
		int a = html.indexOf("<td>OperatorAccess Company A</td>");
		assertThat(b).as("company B present").isPositive();
		assertThat(b).as("newest first: B < D < C < A").isLessThan(d);
		assertThat(d).isLessThan(c);
		assertThat(c).isLessThan(a);

		String rowA = rowOf(html, "OperatorAccess Company A");
		assertThat(rowA).contains("<td>" + FIRST_MANAGER_EMAIL + "</td>")
			.doesNotContain(SECOND_MANAGER_EMAIL)
			.doesNotContain(TECHNICIAN_A_EMAIL)
			.contains("<td>2026-01-10</td>")
			.contains(">Aktywna</span>");
		String rowB = rowOf(html, "OperatorAccess Company B");
		assertThat(rowB).contains("<td></td>")
			.doesNotContain(TECHNICIAN_B_EMAIL)
			.contains("<td>2026-03-05</td>")
			.contains(">Oczekuje</span>");
		assertThat(rowOf(html, "OperatorAccess Company C")).contains(">Odrzucona</span>");
		assertThat(rowOf(html, "OperatorAccess Company D")).contains(">Zablokowana</span>");
	}

	@Test
	void listWithNoCompaniesShowsEmptyState() throws Exception {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Long.class))
			.as("another test class leaked a company; the empty state cannot be shown")
			.isZero();
		MockHttpSession operator = loginOperator();

		mockMvc.perform(get("/operator/companies").session(operator))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(EMPTY_STATE)))
			.andExpect(content().string(not(containsString("<table>"))));
	}

	@Test
	void homeRedirectsOperatorToCompanyListAndSessionSurvivesSeveralRequests() throws Exception {
		MockHttpSession operator = loginOperator();

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/").session(operator))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/operator/companies"));
			mockMvc.perform(get("/operator/companies").session(operator))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(OPERATOR_EMAIL)));
		}
	}

	@Test
	void operatorTopbarShowsOnlyCompanyListLinkAndOperatorBadge() throws Exception {
		MockHttpSession operator = loginOperator();

		String html = mockMvc.perform(get("/operator/companies").session(operator))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		String topbar = html.substring(html.indexOf("<header class=\"topbar\">"), html.indexOf("</header>"));
		assertThat(topbar).contains("<a href=\"/operator/companies\">Firmy</a>")
			.contains(">Operator</span>")
			.doesNotContain(">Kierownik</span>")
			.doesNotContain(">Technik</span>")
			.doesNotContain("href=\"/parts\"")
			.doesNotContain("href=\"/deliveries/new\"")
			.doesNotContain("href=\"/projects\"")
			.doesNotContain("href=\"/picking\"")
			.doesNotContain("href=\"/manager/accounts\"");
	}

	@Test
	void managerAndTechnicianKeepTheirTopbarWithoutCompanyListLink() throws Exception {
		Company company = companyFixtures.company("OperatorAccess Company A");
		companyFixtures.account(company, SECOND_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(company, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);

		String managerHtml = mockMvc.perform(get("/").session(loginAs(SECOND_MANAGER_EMAIL)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(managerHtml).contains("href=\"/parts\"")
			.contains("href=\"/manager/accounts\"")
			.contains(">Kierownik</span>")
			.doesNotContain(">Technik</span>")
			.doesNotContain(">Operator</span>")
			.doesNotContain("href=\"/operator/companies\"");

		String technicianHtml = mockMvc.perform(get("/").session(loginAs(TECHNICIAN_A_EMAIL)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(technicianHtml).contains("href=\"/parts\"")
			.doesNotContain("href=\"/manager/accounts\"")
			.contains(">Technik</span>")
			.doesNotContain(">Kierownik</span>")
			.doesNotContain(">Operator</span>")
			.doesNotContain("href=\"/operator/companies\"");
	}

	// ---------- Who may open the list ----------

	@Test
	void managerAndTechnicianGet403OnCompanyList() throws Exception {
		Company company = companyFixtures.company("OperatorAccess Company A");
		companyFixtures.account(company, SECOND_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(company, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN, true);

		mockMvc.perform(get("/operator/companies").session(loginAs(SECOND_MANAGER_EMAIL)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/operator/companies").session(loginAs(TECHNICIAN_A_EMAIL)))
			.andExpect(status().isForbidden());
	}

	@Test
	void anonymousIsRedirectedToLoginFromCompanyList() throws Exception {
		mockMvc.perform(get("/operator/companies"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
	}

	// ---------- Operator → 403 on every warehouse controller ----------

	/** A company with a manager, a part, a project with one BOM line, no orders. */
	private record Warehouse(Company company, Account manager, Part part, Project project) {
	}

	private Warehouse warehouse() {
		Company company = companyFixtures.company("OperatorAccess Warehouse Co");
		Account manager = companyFixtures.account(company, SECOND_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		Part part = companyFixtures.part(company, "OperatorAccess Resistor", 10, "A-1");
		Part otherPart = companyFixtures.part(company, "OperatorAccess Capacitor", 4, "A-2");
		Project project = companyFixtures.project(company, "OperatorAccess Project", true);
		companyFixtures.bomLine(project, otherPart, 2);
		return new Warehouse(company, manager, part, project);
	}

	private void expectForbidden(MockHttpSession operator, MockHttpServletRequestBuilder request) throws Exception {
		mockMvc.perform(request.session(operator)).andExpect(status().isForbidden());
	}

	private int partQuantity(Part part) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part.getId());
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	@Test
	void operatorGets403OnPartControllerAndCreatesNothing() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/parts"));
		expectForbidden(operator, post("/parts").with(csrf())
				.param("name", "OperatorAccess New Part")
				.param("quantity", "5")
				.param("locations", "B-1"));
		expectForbidden(operator, post("/parts/{id}", warehouse.part().getId()).with(csrf())
				.param("name", "OperatorAccess Renamed")
				.param("locations", "B-1"));

		assertThat(count("SELECT count(*) FROM parts WHERE company_id = ?", warehouse.company().getId())).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM parts WHERE id = ?", String.class,
				warehouse.part().getId())).isEqualTo("OperatorAccess Resistor");
	}

	@Test
	void operatorGets403OnPartImportController() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/parts/import"));
		expectForbidden(operator, post("/parts/import/confirm").with(csrf()).param("token", "any-token"));

		assertThat(count("SELECT count(*) FROM parts WHERE company_id = ?", warehouse.company().getId())).isEqualTo(2);
	}

	@Test
	void operatorGets403OnDeliveryControllerAndStockIsUnchanged() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/deliveries/new"));
		expectForbidden(operator, post("/deliveries").with(csrf())
				.param("rows", "5")
				.param("partId0", warehouse.part().getId().toString())
				.param("quantity0", "7"));

		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
	}

	@Test
	void operatorGets403OnStockCorrectionControllerAndStockIsUnchanged() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/parts/{id}/correction", warehouse.part().getId()));
		expectForbidden(operator, post("/parts/{id}/correction/adjust", warehouse.part().getId()).with(csrf())
				.param("delta", "-3")
				.param("reason", "operator attempt"));

		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
		assertThat(count("SELECT count(*) FROM stock_corrections WHERE part_id = ?", warehouse.part().getId()))
			.isZero();
	}

	@Test
	void operatorGets403OnProjectControllerAndProjectIsUnchanged() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/projects"));
		expectForbidden(operator, get("/projects/{id}", warehouse.project().getId()));
		expectForbidden(operator, post("/projects/{id}", warehouse.project().getId()).with(csrf())
				.param("name", "OperatorAccess Renamed Project"));

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM projects WHERE id = ?", String.class,
				warehouse.project().getId())).isEqualTo("OperatorAccess Project");
	}

	@Test
	void operatorGets403OnProjectBomControllerAndBomIsUnchanged() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, post("/projects/{id}/bom", warehouse.project().getId()).with(csrf())
				.param("partId", warehouse.part().getId().toString())
				.param("quantityPerUnit", "3"));

		assertThat(count("SELECT count(*) FROM bom_lines WHERE project_id = ?", warehouse.project().getId()))
			.isEqualTo(1);
	}

	@Test
	void operatorGets403OnProjectLinkControllerAndNoLinkIsAdded() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, post("/projects/{id}/links", warehouse.project().getId()).with(csrf())
				.param("url", "https://example.com/datasheet")
				.param("label", "Datasheet"));

		assertThat(count("SELECT count(*) FROM project_links WHERE project_id = ?", warehouse.project().getId()))
			.isZero();
	}

	@Test
	void operatorGets403OnOrderControllerAndNoOrderIsCreated() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/orders"));
		expectForbidden(operator, post("/orders").with(csrf())
				.param("projectId", warehouse.project().getId().toString())
				.param("quantityUnits", "1")
				.param("priority", "NORMAL")
				.param("requiredDate", "2099-01-01"));

		assertThat(count("SELECT count(*) FROM orders WHERE company_id = ?", warehouse.company().getId())).isZero();
		assertThat(partQuantity(warehouse.part())).isEqualTo(10);
	}

	@Test
	void operatorGets403OnPickingController() throws Exception {
		warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/picking"));
	}

	@Test
	void operatorGets403OnShoppingListController() throws Exception {
		warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/purchasing"));
		expectForbidden(operator, get("/purchasing/export"));
	}

	@Test
	void operatorGets403OnCompanyAccountControllerAndAccountIsUnchanged() throws Exception {
		Warehouse warehouse = warehouse();
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/manager/accounts"));
		expectForbidden(operator, post("/manager/accounts/{id}/deactivate", warehouse.manager().getId()).with(csrf()));
		expectForbidden(operator, post("/manager/accounts").with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "created-by-operator@operator-access.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD));

		assertThat(jdbcTemplate.queryForObject("SELECT active FROM accounts WHERE id = ?", Boolean.class,
				warehouse.manager().getId())).isTrue();
		assertThat(count("SELECT count(*) FROM accounts WHERE email = ?",
				"created-by-operator@operator-access.example")).isZero();
	}

	@Test
	void operatorGets403OnManagerPingController() throws Exception {
		MockHttpSession operator = loginOperator();

		expectForbidden(operator, get("/manager/ping"));
	}

}

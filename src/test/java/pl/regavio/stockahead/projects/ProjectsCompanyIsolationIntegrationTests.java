package pl.regavio.stockahead.projects;

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

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.parts.Part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The S-02 guardrail for projects: company B's users never see, edit,
 * deactivate or reactivate company A's projects, their BOM lines or links (a
 * foreign id is a 404, exactly like a missing one), project names are unique
 * only within a company, a BOM line never references another company's part,
 * and an order never draws on another company's project. Against real
 * Postgres via Testcontainers, through the real HTTP routes and form login,
 * so the principal carries its company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class ProjectsCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@projects-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@projects-isolation.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@projects-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String DUPLICATE_NAME_ERROR = "Projekt o tej nazwie już istnieje.";

	private static final String PART_UNAVAILABLE_ERROR = "Część nieaktywna lub nie istnieje.";

	private static final String PROJECT_UNAVAILABLE_ERROR = "Projekt nieaktywny lub nie istnieje.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Company companyA;

	private Company companyB;

	@BeforeEach
	void setUp() {
		cleanUp();
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@projects-isolation.example'");
		companyFixtures.cleanUp();
		companyA = null;
		companyB = null;
	}

	// ---- fixtures -----------------------------------------------------

	private Company companyA() {
		if (companyA == null) {
			companyA = companyFixtures.company("ProjectsCompanyIsolationIntegrationTests A");
		}
		return companyA;
	}

	private Company companyB() {
		if (companyB == null) {
			companyB = companyFixtures.company("ProjectsCompanyIsolationIntegrationTests B");
		}
		return companyB;
	}

	private MockHttpSession managerA() throws Exception {
		companyFixtures.account(companyA(), MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		return loginAs(MANAGER_A_EMAIL);
	}

	private MockHttpSession managerB() throws Exception {
		companyFixtures.account(companyB(), MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		return loginAs(MANAGER_B_EMAIL);
	}

	private MockHttpSession technicianB() throws Exception {
		companyFixtures.account(companyB(), TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		return loginAs(TECHNICIAN_B_EMAIL);
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long addLink(Long projectId, String url, String label) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO project_links (project_id, url, label) VALUES (?, ?, ?) RETURNING id", Long.class,
				projectId, url, label);
	}

	private Long lineIdOf(Long projectId, Long partId) {
		return jdbcTemplate.queryForObject("SELECT id FROM bom_lines WHERE project_id = ? AND part_id = ?",
				Long.class, projectId, partId);
	}

	private String nameOf(Long projectId) {
		return jdbcTemplate.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, projectId);
	}

	private boolean isActive(Long projectId) {
		return Boolean.TRUE.equals(
				jdbcTemplate.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean.class, projectId));
	}

	private List<String> bomLinesOf(Long projectId) {
		return jdbcTemplate.queryForList(
				"SELECT part_id || 'x' || quantity_per_unit FROM bom_lines WHERE project_id = ? ORDER BY id",
				String.class, projectId);
	}

	private List<String> linksOf(Long projectId) {
		return jdbcTemplate.queryForList(
				"SELECT url || '|' || COALESCE(label, '') FROM project_links WHERE project_id = ? ORDER BY id",
				String.class, projectId);
	}

	private int bomLineCount() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bom_lines", Integer.class);
		return count == null ? 0 : count;
	}

	private int orderCount() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
		return count == null ? 0 : count;
	}

	private int reservedOf(Long partId) {
		Integer reserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				partId);
		return reserved == null ? 0 : reserved;
	}

	// ---- by-id routes -------------------------------------------------

	@Test
	void otherCompanysManagerGets404OnEveryProjectRouteAndChangesNothing() throws Exception {
		Project projectA = companyFixtures.project(companyA(), "Alpha Board", true);
		Part partA = companyFixtures.part(companyA(), "Alpha Resistor", 5, "A1");
		Part otherPartA = companyFixtures.part(companyA(), "Alpha Capacitor", 5, "A2");
		companyFixtures.bomLine(projectA, partA, 2);
		Long lineA = lineIdOf(projectA.getId(), partA.getId());
		Long linkA = addLink(projectA.getId(), "https://alpha.example/docs", "Alpha docs");
		List<String> bomBefore = bomLinesOf(projectA.getId());
		List<String> linksBefore = linksOf(projectA.getId());
		String base = "/projects/" + projectA.getId();
		MockHttpSession session = managerB();

		mockMvc.perform(get(base).session(session))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base).session(session).with(csrf())
				.param("name", "Hijacked"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base + "/deactivate").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		assertThat(isActive(projectA.getId())).isTrue();

		jdbcTemplate.update("UPDATE projects SET active = false WHERE id = ?", projectA.getId());
		mockMvc.perform(post(base + "/reactivate").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		assertThat(isActive(projectA.getId())).isFalse();
		jdbcTemplate.update("UPDATE projects SET active = true WHERE id = ?", projectA.getId());

		mockMvc.perform(post(base + "/bom").session(session).with(csrf())
				.param("partId", otherPartA.getId().toString())
				.param("quantityPerUnit", "1"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base + "/bom/" + lineA).session(session).with(csrf())
				.param("quantityPerUnit", "9"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base + "/bom/" + lineA + "/delete").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base + "/links").session(session).with(csrf())
				.param("url", "https://hijack.example")
				.param("label", "Hijack"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(base + "/links/" + linkA + "/delete").session(session).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(nameOf(projectA.getId())).isEqualTo("Alpha Board");
		assertThat(isActive(projectA.getId())).isTrue();
		assertThat(bomLinesOf(projectA.getId())).isEqualTo(bomBefore);
		assertThat(linksOf(projectA.getId())).isEqualTo(linksBefore);
	}

	@Test
	void otherCompanysTechnicianGets404OnProjectDetail() throws Exception {
		Project projectA = companyFixtures.project(companyA(), "Alpha Board", true);

		mockMvc.perform(get("/projects/" + projectA.getId()).session(technicianB()))
			.andExpect(status().isNotFound());
	}

	// ---- list ---------------------------------------------------------

	@Test
	void projectListShowsOnlyTheUsersOwnCompany() throws Exception {
		companyFixtures.project(companyA(), "Alpha Active Board", true);
		companyFixtures.project(companyA(), "Alpha Retired Board", false);
		companyFixtures.project(companyB(), "Beta Board", true);
		MockHttpSession manager = managerB();

		mockMvc.perform(get("/projects").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Beta Board")))
			.andExpect(content().string(not(containsString("Alpha Active Board"))))
			.andExpect(content().string(not(containsString("Alpha Retired Board"))));
		mockMvc.perform(get("/projects").param("showInactive", "true").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Beta Board")))
			.andExpect(content().string(not(containsString("Alpha Active Board"))))
			.andExpect(content().string(not(containsString("Alpha Retired Board"))));
		mockMvc.perform(get("/projects").session(technicianB()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Beta Board")))
			.andExpect(content().string(not(containsString("Alpha Active Board"))));
	}

	// ---- names --------------------------------------------------------

	@Test
	void secondCompanyReusesAnotherCompanysProjectNameButNotItsOwn() throws Exception {
		Project projectA = companyFixtures.project(companyA(), "Shared Board", true);
		Project betaOne = companyFixtures.project(companyB(), "Beta One", true);
		Project betaTwo = companyFixtures.project(companyB(), "Beta Two", true);
		MockHttpSession session = managerB();

		mockMvc.perform(post("/projects").session(session).with(csrf())
				.param("name", "Shared Board"))
			.andExpect(status().is3xxRedirection());
		Long createdB = jdbcTemplate.queryForObject(
				"SELECT id FROM projects WHERE company_id = ? AND name = 'Shared Board'", Long.class,
				companyB().getId());
		assertThat(createdB).isNotEqualTo(projectA.getId());

		companyFixtures.project(companyA(), "Alpha Two", true);
		mockMvc.perform(post("/projects/" + betaOne.getId()).session(session).with(csrf())
				.param("name", "Alpha Two"))
			.andExpect(status().is3xxRedirection());
		assertThat(nameOf(betaOne.getId())).isEqualTo("Alpha Two");

		mockMvc.perform(post("/projects/" + betaTwo.getId()).session(session).with(csrf())
				.param("name", "Alpha Two"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DUPLICATE_NAME_ERROR)));
		assertThat(nameOf(betaTwo.getId())).isEqualTo("Beta Two");
		assertThat(nameOf(projectA.getId())).isEqualTo("Shared Board");
	}

	// ---- BOM part reference -------------------------------------------

	@Test
	void anotherCompanysPartPostedToABomIsUnavailableAndWritesNoLine() throws Exception {
		Part partA = companyFixtures.part(companyA(), "Alpha Resistor", 5, "A1");
		Project projectB = companyFixtures.project(companyB(), "Beta Board", true);

		mockMvc.perform(post("/projects/" + projectB.getId() + "/bom").session(managerB()).with(csrf())
				.param("partId", partA.getId().toString())
				.param("quantityPerUnit", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PART_UNAVAILABLE_ERROR)));

		assertThat(bomLineCount()).isZero();
	}

	// ---- order's project ----------------------------------------------

	@Test
	void orderFormOffersOnlyOwnProjectsAndRejectsAnotherCompanysProject() throws Exception {
		Part partA = companyFixtures.part(companyA(), "Alpha Resistor", 5, "A1");
		Project projectA = companyFixtures.project(companyA(), "Alpha Board", true);
		companyFixtures.bomLine(projectA, partA, 1);
		companyFixtures.project(companyB(), "Beta Board", true);
		String requiredDate = LocalDate.now().plusDays(1).toString();

		mockMvc.perform(post("/orders").session(managerA()).with(csrf())
				.param("projectId", projectA.getId().toString())
				.param("quantityUnits", "2")
				.param("priority", "NORMAL")
				.param("requiredDate", requiredDate))
			.andExpect(status().is3xxRedirection());
		assertThat(orderCount()).isEqualTo(1);
		assertThat(reservedOf(partA.getId())).isEqualTo(2);

		MockHttpSession session = managerB();
		mockMvc.perform(get("/orders/new").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Beta Board")))
			.andExpect(content().string(not(containsString("Alpha Board"))));

		mockMvc.perform(post("/orders").session(session).with(csrf())
				.param("projectId", projectA.getId().toString())
				.param("quantityUnits", "3")
				.param("priority", "HIGH")
				.param("requiredDate", requiredDate))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PROJECT_UNAVAILABLE_ERROR)))
			.andExpect(content().string(not(containsString("Alpha Board"))));

		assertThat(orderCount()).isEqualTo(1);
		assertThat(reservedOf(partA.getId())).isEqualTo(2);
	}

}

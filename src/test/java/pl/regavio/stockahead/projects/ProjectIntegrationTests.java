package pl.regavio.stockahead.projects;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
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
 * Covers the project lifecycle (create, rename, deactivate, reactivate),
 * the read-only detail page and the manager/technician role split on every
 * project route, against a real Postgres via Testcontainers. Deliberately
 * not {@code @Transactional}: each MockMvc request commits its own
 * transaction so the DB {@code UNIQUE} constraint on {@code projects.name}
 * — the controller's only duplicate-name check — genuinely fires.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class ProjectIntegrationTests {

	private static final String MANAGER_EMAIL = "projects-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "projects-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String DUPLICATE_NAME_ERROR = "Projekt o tej nazwie już istnieje.";

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

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private void seedManager() {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
	}

	private void seedTechnician() {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
	}

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("ProjectIntegrationTests Co");
		}
		return company;
	}

	private void seedAccount(String email, Role role) {
		companyFixtures.account(company(), email, PASSWORD, role, true);
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedProject(String name, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, ?) RETURNING id", Long.class, name, active));
	}

	private Long seedPart(String name, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, 10, ?) RETURNING id", Long.class, name,
				active));
	}

	private List<String> projectNames() {
		return jdbcTemplate.queryForList("SELECT name FROM projects ORDER BY name", String.class);
	}

	private String nameOf(Long projectId) {
		return jdbcTemplate.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, projectId);
	}

	private boolean isActive(Long projectId) {
		return jdbcTemplate.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean.class, projectId);
	}

	// ---- create -----------------------------------------------------------

	@Test
	void managerCreatesProjectAndIsRedirectedToItsDetailPage() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String location = mockMvc
			.perform(post("/projects").session(session).with(csrf()).param("name", "  Sterownik A  "))
			.andExpect(status().is3xxRedirection())
			.andReturn().getResponse().getHeader("Location");

		List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM projects WHERE name = ?", Long.class,
				"Sterownik A");
		assertThat(ids).hasSize(1);
		Long id = ids.get(0);
		assertThat(location).isEqualTo("/projects/" + id);
		assertThat(isActive(id)).isTrue();

		mockMvc.perform(get("/projects/" + id).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Sterownik A")));
	}

	@Test
	void blankNameReRendersFormWithErrorAndCreatesNoRow() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects").session(session).with(csrf()).param("name", "   "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nazwa projektu jest wymagana.")));

		assertThat(projectNames()).isEmpty();
	}

	@Test
	void tooLongNameReRendersFormWithErrorAndSubmittedNameAndCreatesNoRow() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		String tooLong = "x".repeat(256);

		mockMvc.perform(post("/projects").session(session).with(csrf()).param("name", tooLong))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nazwa projektu może mieć maksymalnie 255 znaków.")))
			.andExpect(content().string(containsString(tooLong)));

		assertThat(projectNames()).isEmpty();
	}

	@Test
	void duplicateNameOnCreateHitsDatabaseConstraintAndRendersFriendlyError() throws Exception {
		seedManager();
		Long existingId = seedProject("Sterownik A", true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects").session(session).with(csrf()).param("name", " Sterownik A "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DUPLICATE_NAME_ERROR)))
			.andExpect(content().string(containsString("Sterownik A")));

		assertThat(projectNames()).containsExactly("Sterownik A");
		assertThat(nameOf(existingId)).isEqualTo("Sterownik A");
		assertThat(isActive(existingId)).isTrue();
	}

	@Test
	void namesDifferingOnlyByCaseMayCoexist() throws Exception {
		seedManager();
		seedProject("Sterownik A", true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects").session(session).with(csrf()).param("name", "sterownik a"))
			.andExpect(status().is3xxRedirection());

		assertThat(projectNames()).containsExactlyInAnyOrder("Sterownik A", "sterownik a");
	}

	// ---- rename -----------------------------------------------------------

	@Test
	void managerRenamesProjectAndIsRedirectedToItsDetailPage() throws Exception {
		seedManager();
		Long id = seedProject("Old Name", true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/projects/" + id).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("action=\"/projects/" + id + "\"")))
			.andExpect(content().string(containsString("value=\"Old Name\"")));

		mockMvc.perform(post("/projects/" + id).session(session).with(csrf()).param("name", " New Name "))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + id));

		assertThat(nameOf(id)).isEqualTo("New Name");
	}

	@Test
	void blankNameOnRenameReRendersFormAndLeavesNameUnchanged() throws Exception {
		seedManager();
		Long id = seedProject("Keep Me", true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects/" + id).session(session).with(csrf()).param("name", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nazwa projektu jest wymagana.")));

		assertThat(nameOf(id)).isEqualTo("Keep Me");
	}

	@Test
	void duplicateNameOnRenameHitsDatabaseConstraintAndLeavesBothRowsUnchanged() throws Exception {
		seedManager();
		Long firstId = seedProject("Project One", true);
		Long secondId = seedProject("Project Two", true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects/" + firstId).session(session).with(csrf()).param("name", "Project Two"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DUPLICATE_NAME_ERROR)))
			.andExpect(content().string(containsString("Project Two")));

		assertThat(nameOf(firstId)).isEqualTo("Project One");
		assertThat(nameOf(secondId)).isEqualTo("Project Two");
	}

	@Test
	void unknownProjectIdIs404() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/projects/999999").session(session))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/projects/999999").session(session).with(csrf()).param("name", "Anything"))
			.andExpect(status().isNotFound());
	}

	// ---- active / inactive visibility --------------------------------------

	@Test
	void deactivatedProjectIsHiddenFromDefaultListAndFromTechnicians() throws Exception {
		seedManager();
		seedTechnician();
		seedProject("Active Board", true);
		Long retiredId = seedProject("Retired Board", true);
		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects/" + retiredId + "/deactivate").session(managerSession).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects"));

		assertThat(isActive(retiredId)).isFalse();

		mockMvc.perform(get("/projects").session(managerSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Board")))
			.andExpect(content().string(not(containsString("Retired Board"))));

		mockMvc.perform(get("/projects").session(managerSession).param("showInactive", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Board")))
			.andExpect(content().string(containsString("Retired Board")));

		mockMvc.perform(get("/projects/" + retiredId).session(managerSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Retired Board")));

		MockHttpSession technicianSession = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/projects").session(technicianSession).param("showInactive", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Board")))
			.andExpect(content().string(not(containsString("Retired Board"))));

		mockMvc.perform(get("/projects/" + retiredId).session(technicianSession))
			.andExpect(status().isNotFound());
	}

	@Test
	void reactivatingProjectMakesItReappearInDefaultList() throws Exception {
		seedManager();
		Long id = seedProject("Mothballed Board", false);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/projects/" + id + "/reactivate").session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects?showInactive=true"));

		assertThat(isActive(id)).isTrue();

		mockMvc.perform(get("/projects").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Mothballed Board")));
	}

	// ---- detail page ------------------------------------------------------

	@Test
	void bothRolesGet200OnListAndActiveProjectDetail() throws Exception {
		seedManager();
		seedTechnician();
		Long id = seedProject("Visible Board", true);

		for (String email : List.of(MANAGER_EMAIL, TECHNICIAN_EMAIL)) {
			MockHttpSession session = loginAs(email);
			mockMvc.perform(get("/projects").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Visible Board")));
			mockMvc.perform(get("/projects/" + id).session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Visible Board")))
				.andExpect(content().string(containsString("Brak pozycji w liście materiałowej.")))
				.andExpect(content().string(containsString("Brak linków do dokumentacji.")));
		}
	}

	@Test
	void detailPageShowsBomLinesWithPartNamesAndLinks() throws Exception {
		seedTechnician();
		Long projectId = seedProject("Populated Board", true);
		Long activePartId = seedPart("Resistor 10k", true);
		Long inactivePartId = seedPart("Old Capacitor", false);
		transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.update("INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?)",
					projectId, activePartId, 4);
			jdbcTemplate.update("INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?)",
					projectId, inactivePartId, 2);
			jdbcTemplate.update("INSERT INTO project_links (project_id, url, label) VALUES (?, ?, ?)", projectId,
					"https://example.com/schematic.pdf", "Schemat");
		});

		mockMvc.perform(get("/projects/" + projectId).session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Resistor 10k")))
			.andExpect(content().string(containsString("Old Capacitor")))
			.andExpect(content().string(containsString("(nieaktywna)")))
			.andExpect(content().string(containsString("https://example.com/schematic.pdf")))
			.andExpect(content().string(containsString("Schemat")))
			.andExpect(content().string(not(containsString("Brak pozycji w liście materiałowej."))));
	}

	// ---- role gating --------------------------------------------------

	@Test
	void technicianGets403OnEveryManagerOnlyProjectRoute() throws Exception {
		seedTechnician();
		Long id = seedProject("Guarded Board", true);
		Long inactiveId = seedProject("Guarded Inactive Board", false);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/projects/new").session(session))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects").session(session).with(csrf()).param("name", "Should Not Be Created"))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/projects/" + id).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("action=\"/projects/" + id + "\""))));

		mockMvc.perform(post("/projects/" + id).session(session).with(csrf()).param("name", "Renamed"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects/" + id + "/deactivate").session(session).with(csrf()))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects/" + inactiveId + "/reactivate").session(session).with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(projectNames()).containsExactlyInAnyOrder("Guarded Board", "Guarded Inactive Board");
		assertThat(isActive(id)).isTrue();
		assertThat(isActive(inactiveId)).isFalse();
	}

}

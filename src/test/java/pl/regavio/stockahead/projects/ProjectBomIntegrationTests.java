package pl.regavio.stockahead.projects;

import java.util.List;
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
 * Covers BOM editing on the project detail page (add a line, change its
 * quantity, remove it), the error paths that re-render the detail page, the
 * "nieaktywna" marker for lines on deactivated parts, and the role split on
 * every BOM route, against a real Postgres via Testcontainers. Deliberately
 * not {@code @Transactional}: each MockMvc request commits its own
 * transaction so the DB {@code UNIQUE (project_id, part_id)} constraint — the
 * controller's only duplicate-line check — genuinely fires.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class ProjectBomIntegrationTests {

	private static final String MANAGER_EMAIL = "project-bom-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "project-bom-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String QUANTITY_NOT_INTEGER_ERROR = "Ilość na sztukę musi być liczbą całkowitą.";

	private static final String QUANTITY_NOT_POSITIVE_ERROR = "Ilość na sztukę musi wynosić co najmniej 1.";

	private static final String PART_UNAVAILABLE_ERROR = "Część nieaktywna lub nie istnieje.";

	private static final String DUPLICATE_PART_ERROR =
			"Ta część jest już w liście materiałowej. Zmień ilość w istniejącej pozycji.";

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

	private Long resistorId;

	private Long capacitorId;

	private Long retiredPartId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject("BOM Board", true);
		resistorId = seedPart("Resistor 10k", true);
		capacitorId = seedPart("Capacitor 100n", true);
		retiredPartId = seedPart("Retired Diode", false);
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

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("ProjectBomIntegrationTests Co");
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

	private MockHttpSession managerSession() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		return loginAs(MANAGER_EMAIL);
	}

	private MockHttpSession technicianSession() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		return loginAs(TECHNICIAN_EMAIL);
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

	private Long seedLine(Long project, Long part, int quantityPerUnit) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?) RETURNING id",
				Long.class, project, part, quantityPerUnit));
	}

	private void deactivatePart(Long partId) {
		transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE parts SET active = false WHERE id = ?", partId));
	}

	private int lineCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM bom_lines", Integer.class);
	}

	private int lineCount(Long project, Long part) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM bom_lines WHERE project_id = ? AND part_id = ?",
				Integer.class, project, part);
	}

	private int quantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT quantity_per_unit FROM bom_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private List<Long> lineIds() {
		return jdbcTemplate.queryForList("SELECT id FROM bom_lines ORDER BY id", Long.class);
	}

	/**
	 * Returns the {@code value} attribute of the {@code <input>} with the
	 * given {@code id} in the rendered page.
	 */
	private static String inputValue(String html, String id) {
		Matcher input = Pattern.compile("<input[^>]*\\sid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
		assertThat(input.find()).as("input #%s present", id).isTrue();
		Matcher value = Pattern.compile("\\svalue=\"([^\"]*)\"").matcher(input.group());
		assertThat(value.find()).as("input #%s has a value", id).isTrue();
		return value.group(1);
	}

	// ---- add ----------------------------------------------------------

	@Test
	void managerAddsLineAndDetailPageShowsIt() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
			.param("partId", resistorId.toString())
			.param("quantityPerUnit", " 3 "))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(lineCount()).isEqualTo(1);
		assertThat(lineCount(projectId, resistorId)).isEqualTo(1);
		assertThat(quantityOf(lineIds().get(0))).isEqualTo(3);

		mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Resistor 10k")))
			.andExpect(content().string(containsString("value=\"3\"")))
			.andExpect(content().string(not(containsString("Brak pozycji w liście materiałowej."))));
	}

	@Test
	void addingSamePartTwiceHitsDatabaseConstraintAndRendersFriendlyError() throws Exception {
		Long existingLine = seedLine(projectId, resistorId, 2);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
			.param("partId", resistorId.toString())
			.param("quantityPerUnit", "5"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DUPLICATE_PART_ERROR)))
			.andExpect(content().string(containsString("BOM Board")));

		assertThat(lineCount()).isEqualTo(1);
		assertThat(quantityOf(existingLine)).isEqualTo(2);
	}

	@Test
	void invalidQuantityOnAddIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		for (String[] attempt : new String[][] { { "0", QUANTITY_NOT_POSITIVE_ERROR },
				{ "-1", QUANTITY_NOT_POSITIVE_ERROR }, { "abc", QUANTITY_NOT_INTEGER_ERROR } }) {
			mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
				.param("partId", resistorId.toString())
				.param("quantityPerUnit", attempt[0]))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(attempt[1])))
				.andExpect(content().string(containsString("value=\"" + attempt[0] + "\"")));
		}

		assertThat(lineCount()).isZero();
	}

	@Test
	void addingInactivePartIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
			.param("partId", retiredPartId.toString())
			.param("quantityPerUnit", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PART_UNAVAILABLE_ERROR)));

		assertThat(lineCount()).isZero();
	}

	@Test
	void addingNonexistentPartIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		for (String partId : List.of("999999", "not-a-number", "")) {
			mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
				.param("partId", partId)
				.param("quantityPerUnit", "1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(PART_UNAVAILABLE_ERROR)));
		}

		assertThat(lineCount()).isZero();
	}

	@Test
	void partSelectOffersOnlyActiveParts() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<option value=\"" + resistorId + "\">Resistor 10k</option>")))
			.andExpect(content().string(containsString("<option value=\"" + capacitorId + "\">Capacitor 100n</option>")))
			.andExpect(content().string(not(containsString("Retired Diode"))));
	}

	@Test
	void addingToUnknownProjectIs404() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/999999/bom").session(session).with(csrf())
			.param("partId", resistorId.toString())
			.param("quantityPerUnit", "1"))
			.andExpect(status().isNotFound());

		assertThat(lineCount()).isZero();
	}

	@Test
	void managerCanEditBomOfInactiveProject() throws Exception {
		Long inactiveProject = seedProject("Mothballed Board", false);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + inactiveProject + "/bom").session(session).with(csrf())
			.param("partId", capacitorId.toString())
			.param("quantityPerUnit", "2"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + inactiveProject));

		assertThat(lineCount(inactiveProject, capacitorId)).isEqualTo(1);
	}

	// ---- change quantity ------------------------------------------------

	@Test
	void managerChangesLineQuantity() throws Exception {
		Long lineId = seedLine(projectId, resistorId, 2);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + lineId).session(session).with(csrf())
			.param("quantityPerUnit", "7"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(quantityOf(lineId)).isEqualTo(7);
	}

	@Test
	void invalidQuantityOnChangeIsRejectedAndOnlyThatRowShowsSubmittedValue() throws Exception {
		Long lineId = seedLine(projectId, resistorId, 2);
		Long otherLineId = seedLine(projectId, capacitorId, 4);
		MockHttpSession session = managerSession();

		for (String[] attempt : new String[][] { { "0", QUANTITY_NOT_POSITIVE_ERROR },
				{ "-1", QUANTITY_NOT_POSITIVE_ERROR }, { "abc", QUANTITY_NOT_INTEGER_ERROR } }) {
			String html = mockMvc.perform(post("/projects/" + projectId + "/bom/" + lineId).session(session)
				.with(csrf())
				.param("quantityPerUnit", attempt[0]))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(attempt[1])))
				.andReturn().getResponse().getContentAsString();

			assertThat(inputValue(html, "quantityPerUnit-" + lineId)).isEqualTo(attempt[0]);
			assertThat(inputValue(html, "quantityPerUnit-" + otherLineId)).isEqualTo("4");
			assertThat(inputValue(html, "quantityPerUnit")).isEmpty();
		}

		assertThat(quantityOf(lineId)).isEqualTo(2);
		assertThat(quantityOf(otherLineId)).isEqualTo(4);
	}

	@Test
	void lineOnDeactivatedPartStaysMarkedAndItsQuantityCanStillBeChanged() throws Exception {
		Long lineId = seedLine(projectId, capacitorId, 2);
		deactivatePart(capacitorId);
		MockHttpSession session = managerSession();

		mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Capacitor 100n")))
			.andExpect(content().string(containsString("(nieaktywna)")));

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + lineId).session(session).with(csrf())
			.param("quantityPerUnit", "6"))
			.andExpect(status().is3xxRedirection());

		assertThat(lineIds()).containsExactly(lineId);
		assertThat(quantityOf(lineId)).isEqualTo(6);
	}

	// ---- remove ---------------------------------------------------------

	@Test
	void managerRemovesLine() throws Exception {
		Long removedLine = seedLine(projectId, resistorId, 2);
		Long keptLine = seedLine(projectId, capacitorId, 3);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + removedLine + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(lineIds()).containsExactly(keptLine);
	}

	// ---- 404 on foreign or unknown lines -----------------------------------

	@Test
	void lineOfAnotherProjectIs404AndChangesNothing() throws Exception {
		Long otherProject = seedProject("Other Board", true);
		Long foreignLine = seedLine(otherProject, resistorId, 2);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + foreignLine).session(session).with(csrf())
			.param("quantityPerUnit", "9"))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + foreignLine + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(lineIds()).containsExactly(foreignLine);
		assertThat(quantityOf(foreignLine)).isEqualTo(2);
	}

	@Test
	void unknownLineOrProjectIs404() throws Exception {
		Long lineId = seedLine(projectId, resistorId, 2);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom/999999").session(session).with(csrf())
			.param("quantityPerUnit", "9"))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/projects/999999/bom/" + lineId).session(session).with(csrf())
			.param("quantityPerUnit", "9"))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/projects/999999/bom/" + lineId + "/delete").session(session).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(lineIds()).containsExactly(lineId);
		assertThat(quantityOf(lineId)).isEqualTo(2);
	}

	// ---- role gating ----------------------------------------------------

	@Test
	void technicianSeesBomWithoutEditForms() throws Exception {
		seedLine(projectId, resistorId, 2);
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Resistor 10k")))
			.andExpect(content().string(not(containsString("name=\"quantityPerUnit\""))))
			.andExpect(content().string(not(containsString("/bom"))))
			.andExpect(content().string(not(containsString("<select"))));
	}

	@Test
	void technicianGets403OnEveryBomRoute() throws Exception {
		Long lineId = seedLine(projectId, resistorId, 2);
		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/projects/" + projectId + "/bom").session(session).with(csrf())
			.param("partId", capacitorId.toString())
			.param("quantityPerUnit", "1"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + lineId).session(session).with(csrf())
			.param("quantityPerUnit", "9"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects/" + projectId + "/bom/" + lineId + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(lineIds()).containsExactly(lineId);
		assertThat(quantityOf(lineId)).isEqualTo(2);
	}

}

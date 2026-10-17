package pl.regavio.stockahead.parts;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The S-01 guardrail for the parts catalog: company B's users never see,
 * edit, deactivate or reactivate company A's parts (a foreign id is a 404,
 * exactly like a missing one), and part names, shelf spellings, re-spelling,
 * the BOM part picker, import matching and the shelf and name locks all stay
 * within one company. Against real Postgres via Testcontainers, through the
 * real HTTP routes and form login, so the principal carries its company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PartsCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@parts-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@parts-isolation.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@parts-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String CSV_HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

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
		dropSlowdown();
		jdbcTemplate.update("DELETE FROM order_lines");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@parts-isolation.example'");
		companyFixtures.cleanUp();
		companyA = null;
		companyB = null;
	}

	// ---- fixtures -----------------------------------------------------

	private Company companyA() {
		if (companyA == null) {
			companyA = companyFixtures.company("PartsCompanyIsolationIntegrationTests A");
		}
		return companyA;
	}

	private Company companyB() {
		if (companyB == null) {
			companyB = companyFixtures.company("PartsCompanyIsolationIntegrationTests B");
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

	private String nameOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT name FROM parts WHERE id = ?", String.class, partId);
	}

	private boolean isActive(Long partId) {
		return Boolean.TRUE.equals(
				jdbcTemplate.queryForObject("SELECT active FROM parts WHERE id = ?", Boolean.class, partId));
	}

	private List<String> locationsOf(Long partId) {
		return jdbcTemplate.queryForList("SELECT location FROM part_locations WHERE part_id = ? ORDER BY id",
				String.class, partId);
	}

	private Long partIdOf(Company company, String name) {
		return jdbcTemplate.queryForObject("SELECT id FROM parts WHERE company_id = ? AND name = ?", Long.class,
				company.getId(), name);
	}

	private void assertUnchanged(Long partId, String name, List<String> locations, boolean active) {
		assertThat(nameOf(partId)).isEqualTo(name);
		assertThat(locationsOf(partId)).isEqualTo(locations);
		assertThat(isActive(partId)).isEqualTo(active);
	}

	// ---- by-id routes -------------------------------------------------

	@Test
	void otherCompanysManagerGets404OnEveryPartRouteAndChangesNothing() throws Exception {
		Long partA = companyFixtures.part(companyA(), "Alpha Resistor", 5, "a1").getId();
		MockHttpSession session = managerB();

		mockMvc.perform(get("/parts/" + partA + "/edit").session(session))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/parts/" + partA).session(session).with(csrf())
				.param("name", "Hijacked")
				.param("locations", "Z9"))
			.andExpect(status().isNotFound());
		assertUnchanged(partA, "Alpha Resistor", List.of("a1"), true);

		mockMvc.perform(post("/parts/" + partA + "/deactivate").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		assertUnchanged(partA, "Alpha Resistor", List.of("a1"), true);

		jdbcTemplate.update("UPDATE parts SET active = false WHERE id = ?", partA);
		mockMvc.perform(post("/parts/" + partA + "/reactivate").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		assertUnchanged(partA, "Alpha Resistor", List.of("a1"), false);
	}

	// ---- list and search ----------------------------------------------

	@Test
	void listAndSearchShowOnlyTheUsersOwnCompany() throws Exception {
		companyFixtures.part(companyA(), "Alpha Capacitor", 3, "ALPHA-SHELF");
		Long inactiveA = companyFixtures.part(companyA(), "Alpha Retired", 1, "ALPHA-OLD").getId();
		jdbcTemplate.update("UPDATE parts SET active = false WHERE id = ?", inactiveA);
		companyFixtures.part(companyB(), "Beta Diode", 2, "B7");

		for (MockHttpSession session : List.of(managerB(), technicianB())) {
			mockMvc.perform(get("/parts").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Beta Diode")))
				.andExpect(content().string(not(containsString("Alpha Capacitor"))))
				.andExpect(content().string(not(containsString("Alpha Retired"))));
			mockMvc.perform(get("/parts").param("q", "Alpha").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Alpha Capacitor"))));
			mockMvc.perform(get("/parts").param("q", "ALPHA-SHELF").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Alpha Capacitor"))))
				.andExpect(content().string(not(containsString("Beta Diode"))));
			mockMvc.perform(get("/parts").param("showInactive", "true").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Beta Diode")))
				.andExpect(content().string(not(containsString("Alpha Capacitor"))))
				.andExpect(content().string(not(containsString("Alpha Retired"))));
		}

		mockMvc.perform(get("/parts").param("showInactive", "true").session(managerA()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Alpha Capacitor")))
			.andExpect(content().string(containsString("Alpha Retired")))
			.andExpect(content().string(not(containsString("Beta Diode"))));
	}

	// ---- names and shelves --------------------------------------------

	@Test
	void secondCompanyCreatesTheSameNameAndShelfAndEachKeepsItsOwnSpelling() throws Exception {
		Long partA = companyFixtures.part(companyA(), "R 10k", 4, "a1").getId();
		MockHttpSession session = managerB();

		mockMvc.perform(post("/parts").session(session).with(csrf())
				.param("name", "R 10k")
				.param("quantity", "3")
				.param("locations", "A1"))
			.andExpect(status().is3xxRedirection());

		Long partB = partIdOf(companyB(), "R 10k");
		assertThat(partB).isNotEqualTo(partA);
		assertThat(locationsOf(partB)).containsExactly("A1");
		assertThat(locationsOf(partA)).containsExactly("a1");
	}

	@Test
	void respellingAShelfInOneCompanyLeavesTheOtherCompanysShelfAlone() throws Exception {
		Long partA = companyFixtures.part(companyA(), "R 10k", 4, "a1").getId();
		Long otherPartA = companyFixtures.part(companyA(), "C 100n", 2, "a1").getId();
		Long partB = companyFixtures.part(companyB(), "R 10k", 6, "a1").getId();

		mockMvc.perform(post("/parts/" + partA).session(managerA()).with(csrf())
				.param("name", "R 10k")
				.param("locations", "A1"))
			.andExpect(status().is3xxRedirection());

		assertThat(locationsOf(partA)).containsExactly("A1");
		assertThat(locationsOf(otherPartA)).containsExactly("A1");
		assertThat(locationsOf(partB)).containsExactly("a1");
	}

	// ---- BOM picker and import ----------------------------------------

	@Test
	void bomPartPickerListsOnlyTheUsersOwnCompanysParts() throws Exception {
		companyFixtures.part(companyA(), "Alpha Picker Part", 1, "A1");
		companyFixtures.part(companyB(), "Beta Picker Part", 1, "B1");
		Long projectId = jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active, company_id) VALUES (?, true, ?) RETURNING id", Long.class,
				"Isolation Project", companyB().getId());

		mockMvc.perform(get("/projects/" + projectId).session(managerB()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Beta Picker Part")))
			.andExpect(content().string(not(containsString("Alpha Picker Part"))));
	}

	@Test
	void importPreviewClassifiesAnotherCompanysPartNameAsNew() throws Exception {
		companyFixtures.part(companyA(), "Rezystor 10k", 10, "A1");
		MockMultipartFile file = new MockMultipartFile("file", "czesci.csv", "text/csv",
				(CSV_HEADER + "Rezystor 10k;5;B1\r\n").getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/parts/import").file(file).session(managerB()).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("NOWA")))
			.andExpect(content().string(containsString("Nowe części: <strong>1</strong>")))
			.andExpect(content().string(containsString("aktualizowane części: <strong>0</strong>")));
	}

	// ---- locks ----------------------------------------------------------

	/**
	 * Holds every {@code part_locations} write of {@code company} for
	 * {@code seconds}, inside the writer's transaction and so while it holds
	 * its shelf and name locks. Other companies' writes are not slowed.
	 */
	private void installSlowdownFor(Company company, double seconds) {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_isolation_slow_write() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.company_id = %d THEN
						PERFORM pg_sleep(%s);
					END IF;
					RETURN NEW;
				END
				$$""".formatted(company.getId(), seconds));
		jdbcTemplate.execute("CREATE TRIGGER test_isolation_slow_location_trigger "
				+ "BEFORE INSERT OR UPDATE ON part_locations "
				+ "FOR EACH ROW EXECUTE FUNCTION test_isolation_slow_write()");
	}

	private void dropSlowdown() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_isolation_slow_location_trigger ON part_locations");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_isolation_slow_write()");
	}

	/** Waits until some backend is inside the slowdown's {@code pg_sleep}. */
	private void awaitSleepingWriter() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer sleeping = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'PgSleep'",
					Integer.class);
			if (sleeping != null && sleeping > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("company A's create never reached its location write");
	}

	/**
	 * Company A starts creating "R 10k" at "A1" and is held inside its
	 * transaction, with its shelf and name locks taken. Company B then creates
	 * "R 10k" at "a1": its lock keys include the company, so it neither waits
	 * for A nor takes A's spelling, and it commits while A is still held.
	 */
	@Test
	void twoCompaniesCreatingTheSameNameAndShelfAtOnceNeverWaitOnEachOther() throws Exception {
		MockHttpSession sessionA = managerA();
		MockHttpSession sessionB = managerB();
		installSlowdownFor(companyA(), 6);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> createA = executor.submit(() -> mockMvc.perform(post("/parts").session(sessionA)
				.with(csrf())
				.param("name", "R 10k")
				.param("quantity", "1")
				.param("locations", "A1")).andReturn());
			awaitSleepingWriter();

			MvcResult createB = mockMvc.perform(post("/parts").session(sessionB)
				.with(csrf())
				.param("name", "R 10k")
				.param("quantity", "1")
				.param("locations", "a1")).andReturn();

			assertThat(createA.isDone()).as("company A still holds its locks when B commits").isFalse();
			assertThat(createB.getResponse().getStatus()).as("company B's create").isEqualTo(302);
			assertThat(createA.get(20, TimeUnit.SECONDS).getResponse().getStatus()).as("company A's create")
				.isEqualTo(302);
		}
		finally {
			executor.shutdownNow();
			dropSlowdown();
		}

		assertThat(locationsOf(partIdOf(companyA(), "R 10k"))).containsExactly("A1");
		assertThat(locationsOf(partIdOf(companyB(), "R 10k"))).containsExactly("a1");
	}

}

package pl.regavio.stockahead.purchasing;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

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
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The company guardrail for the shopping list: {@code GET /purchasing} and
 * {@code GET /purchasing/export} show only the logged-in manager's company's
 * shortages. Two companies each short on a part with the same name see only
 * their own missing quantity and blocked order; a company with no shortage
 * sees the empty state and a header-only CSV while the other company is
 * short; and a part existing only in the other company never shows up.
 * Against real Postgres via Testcontainers, through the real HTTP routes and
 * form login, so the principal carries its company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PurchasingCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@purchasing-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@purchasing-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String SHARED_PART_NAME = "Capacitor 100nF";

	private static final String PROJECT_A_NAME = "Alpha Board";

	private static final String PROJECT_B_NAME = "Bravo Board";

	private static final String EMPTY_STATE = "Brak braków.";

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final String CSV_HEADER = "Część;Zlecenie;Termin;Brakująca ilość";

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
		companyA = companyFixtures.company("PurchasingCompanyIsolationIntegrationTests A");
		companyB = companyFixtures.company("PurchasingCompanyIsolationIntegrationTests B");
		companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@purchasing-isolation.example'");
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/**
	 * Seeds an open order of {@code project} with one line on {@code part},
	 * {@code required} units required and all of the part's stock reserved, as
	 * the allocator would leave a sole order short on that part.
	 * No BOM line is seeded: the shopping list reads only order lines.
	 * @return the order id
	 */
	private Long seedShortOrder(Company company, Project project, Part part, int required, LocalDate requiredDate) {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, 1, 'NORMAL', ?, ?) RETURNING id",
				Long.class, company.getId(), project.getId(), requiredDate, Timestamp.from(Instant.now()));
		jdbcTemplate.update(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?, ?)",
				company.getId(), orderId, part.getId(), required, part.getQuantity());
		return orderId;
	}

	private String page(MockHttpSession session) throws Exception {
		return mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** The CSV body without its BOM. */
	private String csv(MockHttpSession session) throws Exception {
		byte[] body = mockMvc.perform(get("/purchasing/export").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(body).startsWith(UTF8_BOM);
		return new String(body, UTF8_BOM.length, body.length - UTF8_BOM.length, StandardCharsets.UTF_8);
	}

	private static String orderLink(Long orderId) {
		return "/orders/" + orderId + "\"";
	}

	// ---- same-named part short in both companies --------------------------

	@Test
	void eachCompanySeesOnlyItsOwnShortageOnSameNamedPart() throws Exception {
		Part partA = companyFixtures.part(companyA, SHARED_PART_NAME, 2);
		Part partB = companyFixtures.part(companyB, SHARED_PART_NAME, 1);
		Project projectA = companyFixtures.project(companyA, PROJECT_A_NAME, true);
		Project projectB = companyFixtures.project(companyB, PROJECT_B_NAME, true);
		LocalDate dateA = LocalDate.now().plusDays(5);
		LocalDate dateB = LocalDate.now().plusDays(9);
		// A: 7 required, 2 reserved, so 5 short; B: 4 required, 1 reserved, so 3 short.
		Long orderA = seedShortOrder(companyA, projectA, partA, 7, dateA);
		Long orderB = seedShortOrder(companyB, projectB, partB, 4, dateB);
		String csvRowA = SHARED_PART_NAME + ";" + PROJECT_A_NAME + " (#" + orderA + ");" + dateA + ";5";
		String csvRowB = SHARED_PART_NAME + ";" + PROJECT_B_NAME + " (#" + orderB + ");" + dateB + ";3";

		MockHttpSession sessionA = loginAs(MANAGER_A_EMAIL);
		String pageA = page(sessionA);
		assertThat(pageA).contains(SHARED_PART_NAME)
			.contains("<td>5</td>")
			.contains(PROJECT_A_NAME + " (" + dateA + ", 5)")
			.contains(orderLink(orderA))
			.doesNotContain("<td>3</td>")
			.doesNotContain(PROJECT_B_NAME)
			.doesNotContain(orderLink(orderB));
		assertThat(csv(sessionA)).isEqualTo(CSV_HEADER + "\r\n" + csvRowA);

		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		String pageB = page(sessionB);
		assertThat(pageB).contains(SHARED_PART_NAME)
			.contains("<td>3</td>")
			.contains(PROJECT_B_NAME + " (" + dateB + ", 3)")
			.contains(orderLink(orderB))
			.doesNotContain("<td>5</td>")
			.doesNotContain(PROJECT_A_NAME)
			.doesNotContain(orderLink(orderA));
		assertThat(csv(sessionB)).isEqualTo(CSV_HEADER + "\r\n" + csvRowB);
	}

	// ---- one company short, the other not ---------------------------------

	@Test
	void companyWithoutShortageSeesEmptyStateWhileOtherCompanyIsShort() throws Exception {
		Part partA = companyFixtures.part(companyA, SHARED_PART_NAME, 2);
		Project projectA = companyFixtures.project(companyA, PROJECT_A_NAME, true);
		seedShortOrder(companyA, projectA, partA, 7, LocalDate.now().plusDays(5));

		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		assertThat(page(sessionB)).contains(EMPTY_STATE)
			.doesNotContain(SHARED_PART_NAME)
			.doesNotContain(PROJECT_A_NAME);
		assertThat(csv(sessionB)).isEqualTo(CSV_HEADER);
	}

	// ---- part existing only in the other company --------------------------

	@Test
	void partOnlyInOtherCompanyNeverShowsUp() throws Exception {
		Part partA = companyFixtures.part(companyA, SHARED_PART_NAME, 2);
		Project projectA = companyFixtures.project(companyA, PROJECT_A_NAME, true);
		seedShortOrder(companyA, projectA, partA, 7, LocalDate.now().plusDays(5));
		Part onlyInB = companyFixtures.part(companyB, "Inductor 10uH", 0);
		Project projectB = companyFixtures.project(companyB, PROJECT_B_NAME, true);
		Long orderB = seedShortOrder(companyB, projectB, onlyInB, 6, LocalDate.now().plusDays(8));

		MockHttpSession sessionA = loginAs(MANAGER_A_EMAIL);
		String pageA = page(sessionA);
		assertThat(pageA).contains(SHARED_PART_NAME)
			.doesNotContain("Inductor 10uH")
			.doesNotContain(PROJECT_B_NAME)
			.doesNotContain(orderLink(orderB));
		String csvA = csv(sessionA);
		assertThat(csvA).contains(SHARED_PART_NAME).doesNotContain("Inductor 10uH").doesNotContain(PROJECT_B_NAME);
	}

}

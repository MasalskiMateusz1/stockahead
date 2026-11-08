package pl.regavio.stockahead.account;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The operator approves or rejects a PENDING company from the company list
 * (US-01): approve lets the company's existing sessions into the warehouse,
 * reject (through a confirmation page) deactivates and releases every account
 * of the company so its users fail login generically and its email can
 * register again. A stale action on a company that is no longer PENDING
 * changes nothing, an unknown id answers 404, and only the operator may act.
 * Companies registered through {@code /register} are not tracked by
 * {@link CompanyFixtures}, so this class deletes its own companies (by name
 * prefix) and accounts (by email domain). Registrations send a unique
 * {@code CF-Connecting-IP} from 203.0.113.0/24, a range no other test uses,
 * to stay clear of the shared per-IP rate limit.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OperatorCompanyLifecycleIntegrationTests {

	private static final String NAME_PREFIX = "OperatorLifecycle";

	private static final String PASSWORD = "correct-password";

	private static final String OPERATOR_EMAIL = "operator@operator-lifecycle.example";

	private static final String REGISTRANT_EMAIL = "founder@operator-lifecycle.example";

	private static final String MANAGER_EMAIL = "manager@operator-lifecycle.example";

	private static final String TECHNICIAN_EMAIL = "technician@operator-lifecycle.example";

	private static final String OTHER_MANAGER_EMAIL = "other-manager@operator-lifecycle.example";

	private static final String STATUS_CHANGED = "Status firmy zmienił się — odświeżono listę.";

	private static final AtomicInteger IP_COUNTER = new AtomicInteger();

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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@operator-lifecycle.example'");
		companyFixtures.cleanUp();
		jdbcTemplate.update("DELETE FROM accounts WHERE company_id IN (SELECT id FROM companies WHERE name LIKE ?)",
				NAME_PREFIX + "%");
		jdbcTemplate.update("DELETE FROM companies WHERE name LIKE ?", NAME_PREFIX + "%");
	}

	// ---------- Helpers ----------

	/** Registers a company through {@code /register}, passing the bot check, and expects success. */
	private void register(String companyName, String email) throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(RegistrationBotCheck.FORM_ISSUED_AT_SESSION_ATTRIBUTE, Instant.now().minusSeconds(10));
		int n = IP_COUNTER.incrementAndGet();
		mockMvc.perform(post("/register").with(csrf())
				.session(session)
				.header(ClientAddress.CF_CONNECTING_IP_HEADER, "203.0.113." + (n % 250 + 1))
				.param("companyName", companyName)
				.param("email", email)
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?registered"));
	}

	private long registeredCompanyId(String companyName) {
		return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE name = ?", Long.class, companyName);
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

	private String companyStatus(long companyId) {
		return jdbcTemplate.queryForObject("SELECT status FROM companies WHERE id = ?", String.class, companyId);
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	private String listHtml(MockHttpSession operator) throws Exception {
		return mockMvc.perform(get("/operator/companies").session(operator))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	/** The body rows ({@code <tr>…</tr>}) of the company list that contain {@code companyName}. */
	private static List<String> rowsOf(String html, String companyName) {
		return Arrays.stream(html.split("<tr>"))
			.filter(row -> row.contains("<td>" + companyName + "</td>"))
			.map(row -> row.substring(0, row.indexOf("</tr>")))
			.toList();
	}

	private static String rowOf(String html, String companyName) {
		List<String> rows = rowsOf(html, companyName);
		assertThat(rows).as("rows for %s", companyName).hasSize(1);
		return rows.get(0);
	}

	private static String approveAction(long companyId) {
		return "action=\"/operator/companies/" + companyId + "/approve\"";
	}

	private static String rejectLink(long companyId) {
		return "href=\"/operator/companies/" + companyId + "/reject\"";
	}

	/** Every account of {@code companyId} is active and not released (approve or untouched). */
	private void assertAccountsLive(long companyId) {
		assertThat(count("SELECT count(*) FROM accounts WHERE company_id = ?", companyId)).isPositive();
		assertThat(count("SELECT count(*) FROM accounts WHERE company_id = ? AND (NOT active OR email_released_at IS NOT NULL)",
				companyId)).isZero();
	}

	// ---------- US-01: approve ----------

	@Test
	void registeredManagerWaitsThenReachesWarehouseInSameSessionAfterApproval() throws Exception {
		String name = NAME_PREFIX + " Approved Co";
		register(name, REGISTRANT_EMAIL);
		long companyId = registeredCompanyId(name);
		MockHttpSession manager = loginAs(REGISTRANT_EMAIL);
		mockMvc.perform(get("/").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
		mockMvc.perform(get("/parts").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));

		MockHttpSession operator = loginOperator();
		assertThat(rowOf(listHtml(operator), name)).contains(approveAction(companyId))
			.contains(rejectLink(companyId))
			.contains(">Oczekuje</span>");

		MvcResult approved = mockMvc.perform(post("/operator/companies/{id}/approve", companyId)
				.session(operator).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/operator/companies"))
			.andExpect(flash().attribute("success", "Firma " + name + " została zatwierdzona."))
			.andReturn();
		assertThat(companyStatus(companyId)).isEqualTo("ACTIVE");
		assertAccountsLive(companyId);

		String html = mockMvc.perform(get("/operator/companies").session(operator)
				.flashAttrs(approved.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Firma " + name + " została zatwierdzona.")))
			.andReturn().getResponse().getContentAsString();
		assertThat(rowOf(html, name)).contains(">Aktywna</span>")
			.doesNotContain(approveAction(companyId))
			.doesNotContain(rejectLink(companyId));

		mockMvc.perform(get("/").session(manager))
			.andExpect(status().isOk())
			.andExpect(view().name("dashboard"));
		mockMvc.perform(get("/parts").session(manager))
			.andExpect(status().isOk());
	}

	// ---------- Reject ----------

	@Test
	void rejectViaConfirmPageReleasesAccountAndEmailRegistersAgain() throws Exception {
		String name = NAME_PREFIX + " Rejected Co";
		register(name, REGISTRANT_EMAIL);
		long companyId = registeredCompanyId(name);
		MockHttpSession operator = loginOperator();

		mockMvc.perform(get("/operator/companies/{id}/reject", companyId).session(operator))
			.andExpect(status().isOk())
			.andExpect(view().name("operator-companies-reject"))
			.andExpect(content().string(containsString("<dd>" + name + "</dd>")))
			.andExpect(content().string(containsString("<dd>" + REGISTRANT_EMAIL + "</dd>")))
			.andExpect(content().string(containsString("action=\"/operator/companies/" + companyId + "/reject\"")))
			.andExpect(content().string(containsString(">Odrzuć firmę</button>")))
			.andExpect(content().string(containsString("href=\"/operator/companies\">Anuluj</a>")));
		assertThat(companyStatus(companyId)).as("the confirm page changes nothing").isEqualTo("PENDING");

		mockMvc.perform(post("/operator/companies/{id}/reject", companyId).session(operator).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/operator/companies"))
			.andExpect(flash().attribute("success", "Firma " + name + " została odrzucona."));

		assertThat(companyStatus(companyId)).isEqualTo("REJECTED");
		Map<String, Object> account = jdbcTemplate.queryForMap(
				"SELECT active, email_released_at FROM accounts WHERE company_id = ?", companyId);
		assertThat(account.get("active")).isEqualTo(false);
		assertThat(account.get("email_released_at")).isNotNull();

		mockMvc.perform(formLogin().user(REGISTRANT_EMAIL).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"));

		String rejectedRow = rowOf(listHtml(operator), name);
		assertThat(rejectedRow).contains(">Odrzucona</span>")
			.contains("<td>" + REGISTRANT_EMAIL + "</td>")
			.doesNotContain(approveAction(companyId))
			.doesNotContain(rejectLink(companyId));

		register(name, REGISTRANT_EMAIL);
		List<String> rows = rowsOf(listHtml(operator), name);
		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(row).contains("<td>" + REGISTRANT_EMAIL + "</td>"));
		assertThat(rows).anySatisfy(row -> assertThat(row).contains(">Oczekuje</span>"));
		assertThat(rows).anySatisfy(row -> assertThat(row).contains(">Odrzucona</span>"));

		MockHttpSession newManager = loginAs(REGISTRANT_EMAIL);
		mockMvc.perform(get("/").session(newManager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
	}

	@Test
	void rejectReleasesEveryAccountOfTheCompanyAndNoOtherCompanysAccount() throws Exception {
		Company pending = companyFixtures.company(NAME_PREFIX + " Pending Co", CompanyStatus.PENDING);
		companyFixtures.account(pending, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(pending, TECHNICIAN_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		Company other = companyFixtures.company(NAME_PREFIX + " Other Co", CompanyStatus.PENDING);
		companyFixtures.account(other, OTHER_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession operator = loginOperator();

		mockMvc.perform(post("/operator/companies/{id}/reject", pending.getId()).session(operator).with(csrf()))
			.andExpect(status().is3xxRedirection());

		assertThat(count("SELECT count(*) FROM accounts WHERE company_id = ? AND NOT active AND email_released_at IS NOT NULL",
				pending.getId())).isEqualTo(2);
		assertThat(companyStatus(other.getId())).isEqualTo("PENDING");
		assertAccountsLive(other.getId());
		assertThat(count("SELECT count(*) FROM accounts WHERE company_id IS NULL AND active AND email_released_at IS NULL"))
			.as("the operator stays live").isEqualTo(1);
	}

	@Test
	void openManagerSessionOfRejectedCompanyEndsWithGenericLoginFailure() throws Exception {
		Company pending = companyFixtures.company(NAME_PREFIX + " Session Co", CompanyStatus.PENDING);
		companyFixtures.account(pending, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession manager = loginAs(MANAGER_EMAIL);
		mockMvc.perform(get("/awaiting-approval").session(manager))
			.andExpect(status().isOk());

		mockMvc.perform(post("/operator/companies/{id}/reject", pending.getId()).session(loginOperator()).with(csrf()))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/awaiting-approval").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"));
		assertThat(manager.isInvalid()).isTrue();
	}

	// ---------- Stale clicks ----------

	@Test
	void actionsOnActiveOrRejectedCompanyChangeNothingAndShowStatusChanged() throws Exception {
		Company active = companyFixtures.company(NAME_PREFIX + " Active Co", CompanyStatus.ACTIVE);
		companyFixtures.account(active, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		Company rejected = companyFixtures.company(NAME_PREFIX + " Already Rejected Co", CompanyStatus.REJECTED);
		companyFixtures.releasedAccount(rejected, OTHER_MANAGER_EMAIL, PASSWORD, Role.MANAGER);
		String rejectedReleasedAt = jdbcTemplate.queryForObject(
				"SELECT email_released_at::text FROM accounts WHERE company_id = ?", String.class, rejected.getId());
		MockHttpSession operator = loginOperator();

		for (Company company : List.of(active, rejected)) {
			MvcResult approve = mockMvc.perform(post("/operator/companies/{id}/approve", company.getId())
					.session(operator).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/operator/companies"))
				.andExpect(flash().attribute("info", STATUS_CHANGED))
				.andReturn();
			Map<String, Object> approveFlash = approve.getFlashMap();
			assertThat(approveFlash).doesNotContainKey("success");
			mockMvc.perform(post("/operator/companies/{id}/reject", company.getId()).session(operator).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/operator/companies"))
				.andExpect(flash().attribute("info", STATUS_CHANGED));
			MvcResult rejectPage = mockMvc.perform(get("/operator/companies/{id}/reject", company.getId())
					.session(operator))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/operator/companies"))
				.andExpect(flash().attribute("info", STATUS_CHANGED))
				.andReturn();

			mockMvc.perform(get("/operator/companies").session(operator).flashAttrs(rejectPage.getFlashMap()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(STATUS_CHANGED)));
		}

		assertThat(companyStatus(active.getId())).isEqualTo("ACTIVE");
		assertAccountsLive(active.getId());
		assertThat(companyStatus(rejected.getId())).isEqualTo("REJECTED");
		assertThat(jdbcTemplate.queryForObject("SELECT email_released_at::text FROM accounts WHERE company_id = ?",
				String.class, rejected.getId())).isEqualTo(rejectedReleasedAt);
	}

	// ---------- Unknown id ----------

	@Test
	void unknownCompanyIdAnswers404OnEveryAction() throws Exception {
		long unknownId = count("SELECT coalesce(max(id), 0) + 1000 FROM companies");
		MockHttpSession operator = loginOperator();

		mockMvc.perform(post("/operator/companies/{id}/approve", unknownId).session(operator).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/operator/companies/{id}/reject", unknownId).session(operator))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/operator/companies/{id}/reject", unknownId).session(operator).with(csrf()))
			.andExpect(status().isNotFound());
	}

	// ---------- Who may act ----------

	@Test
	void managerAndTechnicianGet403OnEveryActionAndChangeNothing() throws Exception {
		Company own = companyFixtures.company(NAME_PREFIX + " Own Co", CompanyStatus.ACTIVE);
		companyFixtures.account(own, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(own, TECHNICIAN_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		Company pending = companyFixtures.company(NAME_PREFIX + " Target Co", CompanyStatus.PENDING);
		companyFixtures.account(pending, OTHER_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		for (String email : List.of(MANAGER_EMAIL, TECHNICIAN_EMAIL)) {
			MockHttpSession session = loginAs(email);
			mockMvc.perform(post("/operator/companies/{id}/approve", pending.getId()).session(session).with(csrf()))
				.andExpect(status().isForbidden());
			mockMvc.perform(get("/operator/companies/{id}/reject", pending.getId()).session(session))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/operator/companies/{id}/reject", pending.getId()).session(session).with(csrf()))
				.andExpect(status().isForbidden());
		}

		assertThat(companyStatus(pending.getId())).isEqualTo("PENDING");
		assertAccountsLive(pending.getId());
	}

	@Test
	void anonymousIsRedirectedToLoginFromEveryAction() throws Exception {
		Company pending = companyFixtures.company(NAME_PREFIX + " Anonymous Co", CompanyStatus.PENDING);
		companyFixtures.account(pending, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		for (MockHttpServletRequestBuilder request : List.of(
				post("/operator/companies/{id}/approve", pending.getId()).with(csrf()),
				get("/operator/companies/{id}/reject", pending.getId()),
				post("/operator/companies/{id}/reject", pending.getId()).with(csrf()))) {
			mockMvc.perform(request)
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", containsString("/login")));
		}

		assertThat(companyStatus(pending.getId())).isEqualTo("PENDING");
		assertAccountsLive(pending.getId());
	}

	@Test
	void operatorPostWithoutCsrfTokenIsRejectedAndChangesNothing() throws Exception {
		Company pending = companyFixtures.company(NAME_PREFIX + " Csrf Co", CompanyStatus.PENDING);
		companyFixtures.account(pending, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession operator = loginOperator();

		mockMvc.perform(post("/operator/companies/{id}/approve", pending.getId()).session(operator))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/operator/companies/{id}/reject", pending.getId()).session(operator))
			.andExpect(status().isForbidden());

		assertThat(companyStatus(pending.getId())).isEqualTo("PENDING");
		assertAccountsLive(pending.getId());
	}

	// ---------- List actions ----------

	@Test
	void listShowsActionsOnlyOnPendingRows() throws Exception {
		Company pending = companyFixtures.company(NAME_PREFIX + " List Pending", CompanyStatus.PENDING);
		Company active = companyFixtures.company(NAME_PREFIX + " List Active", CompanyStatus.ACTIVE);
		Company rejected = companyFixtures.company(NAME_PREFIX + " List Rejected", CompanyStatus.REJECTED);
		Company blocked = companyFixtures.company(NAME_PREFIX + " List Blocked", CompanyStatus.BLOCKED);
		MockHttpSession operator = loginOperator();

		String html = listHtml(operator);
		assertThat(html).contains("<th>Status</th><th>Akcje</th>");
		assertThat(rowOf(html, NAME_PREFIX + " List Pending")).contains(approveAction(pending.getId()))
			.contains(rejectLink(pending.getId()))
			.contains(">Zatwierdź</button>")
			.contains(">Odrzuć</a>");
		for (Company company : List.of(active, rejected, blocked)) {
			assertThat(rowOf(html, company.getName())).doesNotContain("Zatwierdź")
				.doesNotContain("Odrzuć")
				.doesNotContain("<form");
		}
	}

}

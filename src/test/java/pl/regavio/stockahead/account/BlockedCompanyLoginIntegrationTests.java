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

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login matrix for a blocked company and the gate wording per company
 * status, against real Postgres: a blocked company's user is refused with
 * {@code /login?blocked} only after the correct password; a wrong password
 * or a deactivated account keeps the generic {@code /login?error}; pending
 * and active companies log in as before. An already-open session of a
 * company blocked after login is gated to {@code /awaiting-approval}, which
 * shows the blocked wording, while a pending company keeps the pending one.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class BlockedCompanyLoginIntegrationTests {

	private static final String PASSWORD = "correct-password";

	private static final String MANAGER_EMAIL = "manager@blocked-login.example";

	private static final String TECHNICIAN_EMAIL = "technician@blocked-login.example";

	private static final String DEACTIVATED_EMAIL = "deactivated@blocked-login.example";

	private static final String LOGIN_BLOCKED_MESSAGE = "Twoja firma została zablokowana przez operatora.";

	private static final String GATE_BLOCKED_TITLE = "Firma zablokowana";

	private static final String GATE_BLOCKED_MESSAGE = "Twoja firma została zablokowana przez operatora. Dane firmy są zachowane.";

	private static final String GATE_PENDING_TITLE = "Oczekiwanie na zatwierdzenie";

	private static final String GATE_PENDING_MESSAGE = "Twoja firma oczekuje na zatwierdzenie przez operatora.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		companyFixtures.cleanUp();
	}

	@AfterEach
	void tearDown() {
		companyFixtures.cleanUp();
	}

	private Company blockedCompany() {
		return companyFixtures.company("BlockedLogin Blocked Co", CompanyStatus.BLOCKED);
	}

	private void expectLoginRedirect(String email, String password, String location) throws Exception {
		mockMvc.perform(formLogin().user(email).password(password))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", location))
			.andExpect(unauthenticated());
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andExpect(authenticated())
			.andReturn().getRequest().getSession();
	}

	// ---------- Login ----------

	@Test
	void blockedCompanyManagerWithCorrectPasswordIsRefusedAsBlocked() throws Exception {
		companyFixtures.account(blockedCompany(), MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		expectLoginRedirect(MANAGER_EMAIL, PASSWORD, "/login?blocked");
	}

	@Test
	void blockedCompanyTechnicianWithCorrectPasswordIsRefusedAsBlocked() throws Exception {
		companyFixtures.account(blockedCompany(), TECHNICIAN_EMAIL, PASSWORD, Role.TECHNICIAN, true);

		expectLoginRedirect(TECHNICIAN_EMAIL, PASSWORD, "/login?blocked");
	}

	@Test
	void blockedCompanyUserWithWrongPasswordGetsGenericError() throws Exception {
		companyFixtures.account(blockedCompany(), MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		expectLoginRedirect(MANAGER_EMAIL, "wrong-password", "/login?error");
	}

	@Test
	void deactivatedAccountOfBlockedCompanyGetsGenericError() throws Exception {
		companyFixtures.account(blockedCompany(), DEACTIVATED_EMAIL, PASSWORD, Role.TECHNICIAN, false);

		expectLoginRedirect(DEACTIVATED_EMAIL, PASSWORD, "/login?error");
	}

	@Test
	void pendingCompanyUserStillLogsInAndLandsOnTheGate() throws Exception {
		Company company = companyFixtures.company("BlockedLogin Pending Co", CompanyStatus.PENDING);
		companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
	}

	@Test
	void activeCompanyUserLogsIn() throws Exception {
		Company company = companyFixtures.company("BlockedLogin Active Co");
		companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk());
	}

	@Test
	void loginPageRendersBlockedMessage() throws Exception {
		mockMvc.perform(get("/login").param("blocked", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(LOGIN_BLOCKED_MESSAGE)));
	}

	@Test
	void loginPageWithoutBlockedParameterHasNoBlockedMessage() throws Exception {
		mockMvc.perform(get("/login").param("error", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(LOGIN_BLOCKED_MESSAGE))));
	}

	// ---------- Gate wording ----------

	@Test
	void openSessionOfCompanyBlockedAfterLoginSeesBlockedGate() throws Exception {
		Company company = companyFixtures.company("BlockedLogin Later Blocked Co");
		companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		jdbcTemplate.update("UPDATE companies SET status = 'BLOCKED' WHERE id = ?", company.getId());

		mockMvc.perform(get("/parts").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
		mockMvc.perform(get("/awaiting-approval").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<title>" + GATE_BLOCKED_TITLE + " — Stockahead</title>")))
			.andExpect(content().string(containsString(GATE_BLOCKED_MESSAGE)))
			.andExpect(content().string(not(containsString(GATE_PENDING_TITLE))))
			.andExpect(content().string(not(containsString(GATE_PENDING_MESSAGE))));
	}

	@Test
	void pendingCompanySessionSeesPendingGate() throws Exception {
		Company company = companyFixtures.company("BlockedLogin Pending Gate Co", CompanyStatus.PENDING);
		companyFixtures.account(company, MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/awaiting-approval").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<title>" + GATE_PENDING_TITLE + " — Stockahead</title>")))
			.andExpect(content().string(containsString(GATE_PENDING_MESSAGE)))
			.andExpect(content().string(not(containsString(GATE_BLOCKED_TITLE))))
			.andExpect(content().string(not(containsString(GATE_BLOCKED_MESSAGE))));
	}

}

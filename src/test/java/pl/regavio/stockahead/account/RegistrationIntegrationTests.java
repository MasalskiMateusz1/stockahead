package pl.regavio.stockahead.account;

import java.util.Map;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Public company registration on {@code /register}: an anonymous visitor
 * creates a PENDING company and its active manager in one transaction, a
 * validation failure or a taken email re-renders the form and creates
 * nothing (no orphan company), CSRF stays enforced, and the registered
 * manager logs in to the awaiting-approval page while the operator sees the
 * company as pending. Rows created through {@code /register} are not tracked
 * by {@link CompanyFixtures}, so this class deletes its own companies (by
 * name prefix) and accounts (by email domain).
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationIntegrationTests {

	private static final String NAME_PREFIX = "RegistrationTest";

	private static final String PASSWORD = "correct-password";

	private static final String REGISTRANT_EMAIL = "founder@registration.example";

	private static final String EXISTING_MANAGER_EMAIL = "taken@registration.example";

	private static final String OPERATOR_EMAIL = "operator@registration.example";

	private static final String DUPLICATE_EMAIL_ERROR = "Konto o tym adresie e-mail już istnieje.";

	private static final String REGISTERED_MESSAGE = "Firma została zarejestrowana i czeka na zatwierdzenie. Zaloguj się.";

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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@registration.example'");
		companyFixtures.cleanUp();
		jdbcTemplate.update("DELETE FROM accounts WHERE company_id IN (SELECT id FROM companies WHERE name LIKE ?)",
				NAME_PREFIX + "%");
		jdbcTemplate.update("DELETE FROM companies WHERE name LIKE ?", NAME_PREFIX + "%");
	}

	private static MockHttpServletRequestBuilder postRegister(String companyName, String email, String password,
			String confirmPassword) {
		return post("/register")
			.param("companyName", companyName)
			.param("email", email)
			.param("password", password)
			.param("confirmPassword", confirmPassword);
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	private long companyCount() {
		return count("SELECT count(*) FROM companies");
	}

	private long accountCount() {
		return count("SELECT count(*) FROM accounts");
	}

	// ---------- GET /register ----------

	@Test
	void anonymousVisitorSeesFormWithoutAnyExistingCompanyName() throws Exception {
		Company existing = companyFixtures.company(NAME_PREFIX + " Secret Existing Co");
		companyFixtures.account(existing, EXISTING_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		mockMvc.perform(get("/register"))
			.andExpect(status().isOk())
			.andExpect(view().name("register"))
			.andExpect(content().string(containsString("name=\"companyName\"")))
			.andExpect(content().string(containsString("name=\"email\"")))
			.andExpect(content().string(containsString("name=\"password\"")))
			.andExpect(content().string(containsString("name=\"confirmPassword\"")))
			.andExpect(content().string(containsString("name=\"_csrf\"")))
			.andExpect(content().string(containsString("href=\"/login\"")))
			.andExpect(content().string(not(containsString("Secret Existing Co"))))
			.andExpect(content().string(not(containsString(EXISTING_MANAGER_EMAIL))));
	}

	@Test
	void authenticatedUserIsRedirectedHome() throws Exception {
		Company company = companyFixtures.company(NAME_PREFIX + " Active Co");
		companyFixtures.account(company, EXISTING_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
		MockHttpSession manager = loginAs(EXISTING_MANAGER_EMAIL);

		mockMvc.perform(get("/register").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"));
	}

	@Test
	void loginPageLinksToRegistrationAndShowsRegisteredMessage() throws Exception {
		mockMvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/register\"")))
			.andExpect(content().string(not(containsString(REGISTERED_MESSAGE))));

		mockMvc.perform(get("/login").param("registered", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(REGISTERED_MESSAGE)));
	}

	// ---------- POST /register: success ----------

	@Test
	void validRegistrationCreatesPendingCompanyAndActiveManager() throws Exception {
		mockMvc.perform(postRegister("  " + NAME_PREFIX + " Founders Co  ", " Founder@Registration.EXAMPLE ",
				PASSWORD, PASSWORD).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?registered"));

		assertThat(count("SELECT count(*) FROM companies WHERE name LIKE ?", NAME_PREFIX + "%")).isEqualTo(1);
		Map<String, Object> company = jdbcTemplate.queryForMap(
				"SELECT id, name, status, created_at FROM companies WHERE name LIKE ?", NAME_PREFIX + "%");
		assertThat(company.get("name")).isEqualTo(NAME_PREFIX + " Founders Co");
		assertThat(company.get("status")).isEqualTo("PENDING");

		assertThat(count("SELECT count(*) FROM accounts WHERE company_id = ?", company.get("id"))).isEqualTo(1);
		Map<String, Object> account = jdbcTemplate.queryForMap(
				"SELECT email, role, active, created_at, password_hash FROM accounts WHERE company_id = ?",
				company.get("id"));
		assertThat(account.get("email")).isEqualTo(REGISTRANT_EMAIL);
		assertThat(account.get("role")).isEqualTo("MANAGER");
		assertThat(account.get("active")).isEqualTo(true);
		assertThat(account.get("created_at")).isEqualTo(company.get("created_at"));
		assertThat((String) account.get("password_hash")).isNotEqualTo(PASSWORD);
	}

	@Test
	void companyNameOfExactly255CharactersIsAccepted() throws Exception {
		String name = NAME_PREFIX + "x".repeat(255 - NAME_PREFIX.length());

		mockMvc.perform(postRegister(name, REGISTRANT_EMAIL, PASSWORD, PASSWORD).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?registered"));

		assertThat(count("SELECT count(*) FROM companies WHERE name = ?", name)).isEqualTo(1);
	}

	@Test
	void secondRegistrationWithExistingCompanyNameSucceeds() throws Exception {
		String name = NAME_PREFIX + " Same Name Co";
		Company existing = companyFixtures.company(name);
		companyFixtures.account(existing, EXISTING_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		mockMvc.perform(postRegister(name, REGISTRANT_EMAIL, PASSWORD, PASSWORD).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?registered"));

		assertThat(count("SELECT count(*) FROM companies WHERE name = ?", name)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM accounts a JOIN companies c ON c.id = a.company_id "
				+ "WHERE a.email = ? AND c.status = 'PENDING' AND c.id <> ?", REGISTRANT_EMAIL, existing.getId()))
			.isEqualTo(1);
	}

	// ---------- POST /register: validation ----------

	private void expectRejected(String companyName, String email, String password, String confirmPassword,
			String expectedError) throws Exception {
		long companiesBefore = companyCount();
		long accountsBefore = accountCount();

		String html = mockMvc.perform(postRegister(companyName, email, password, confirmPassword).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("register"))
			.andExpect(model().attribute("error", expectedError))
			.andExpect(model().attribute("companyName", companyName))
			.andExpect(model().attribute("email", email))
			.andExpect(content().string(containsString(expectedError)))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).doesNotContain("value=\"" + password + "\"");
		if (!confirmPassword.isEmpty()) {
			assertThat(html).doesNotContain("value=\"" + confirmPassword + "\"");
		}
		assertThat(companyCount()).isEqualTo(companiesBefore);
		assertThat(accountCount()).isEqualTo(accountsBefore);
	}

	@Test
	void blankCompanyNameIsRejected() throws Exception {
		expectRejected("   ", REGISTRANT_EMAIL, PASSWORD, PASSWORD, "Nazwa firmy jest wymagana.");
	}

	@Test
	void companyNameLongerThan255IsRejected() throws Exception {
		String name = NAME_PREFIX + "x".repeat(256 - NAME_PREFIX.length());

		expectRejected(name, REGISTRANT_EMAIL, PASSWORD, PASSWORD, "Nazwa firmy może mieć maksymalnie 255 znaków.");
	}

	@Test
	void companyNameIsCheckedBeforeCredentials() throws Exception {
		expectRejected("", "not-an-email", "short", "other", "Nazwa firmy jest wymagana.");
	}

	@Test
	void eachCredentialFailureIsRejectedWithItsMessage() throws Exception {
		String name = NAME_PREFIX + " Credentials Co";
		expectRejected(name, "   ", PASSWORD, PASSWORD, "E-mail jest wymagany.");
		expectRejected(name, "a".repeat(244) + "@registration.example", PASSWORD, PASSWORD,
				"E-mail może mieć maksymalnie 255 znaków.");
		expectRejected(name, "not-an-email", PASSWORD, PASSWORD, "Podaj poprawny adres e-mail.");
		expectRejected(name, REGISTRANT_EMAIL, "too-short", "too-short", "Hasło musi mieć co najmniej 12 znaków.");
		expectRejected(name, REGISTRANT_EMAIL, PASSWORD, "different-password", "Hasła nie są identyczne.");
	}

	// ---------- POST /register: duplicate email ----------

	@Test
	void emailOfExistingCompanyAccountIsRejectedWithoutOrphanCompany() throws Exception {
		Company existing = companyFixtures.company(NAME_PREFIX + " Existing Co");
		companyFixtures.account(existing, EXISTING_MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);

		expectRejected(NAME_PREFIX + " Duplicate Co", " Taken@Registration.EXAMPLE ", PASSWORD, PASSWORD,
				DUPLICATE_EMAIL_ERROR);

		assertThat(count("SELECT count(*) FROM companies WHERE name = ?", NAME_PREFIX + " Duplicate Co")).isZero();
		assertThat(count("SELECT count(*) FROM accounts WHERE lower(btrim(email)) = ?", EXISTING_MANAGER_EMAIL))
			.isEqualTo(1);
	}

	@Test
	void operatorEmailIsRejectedWithoutOrphanCompany() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);

		expectRejected(NAME_PREFIX + " Operator Dup Co", "  OPERATOR@registration.example", PASSWORD, PASSWORD,
				DUPLICATE_EMAIL_ERROR);

		assertThat(count("SELECT count(*) FROM companies WHERE name = ?", NAME_PREFIX + " Operator Dup Co")).isZero();
	}

	// ---------- CSRF ----------

	@Test
	void postWithoutCsrfTokenIsRejected() throws Exception {
		long companiesBefore = companyCount();

		mockMvc.perform(postRegister(NAME_PREFIX + " No Csrf Co", REGISTRANT_EMAIL, PASSWORD, PASSWORD))
			.andExpect(status().isForbidden());

		assertThat(companyCount()).isEqualTo(companiesBefore);
		assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", REGISTRANT_EMAIL)).isZero();
	}

	// ---------- Full flow ----------

	@Test
	void registeredManagerLogsInToAwaitingPageAndOperatorSeesPendingCompany() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);

		mockMvc.perform(postRegister(NAME_PREFIX + " Flow Co", REGISTRANT_EMAIL, PASSWORD, PASSWORD).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?registered"));

		MockHttpSession manager = loginAs(REGISTRANT_EMAIL);
		mockMvc.perform(get("/").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
		mockMvc.perform(get("/parts").session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/awaiting-approval"));
		mockMvc.perform(get("/awaiting-approval").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(AWAITING_MESSAGE)));

		MockHttpSession operator = loginAs(OPERATOR_EMAIL);
		String html = mockMvc.perform(get("/operator/companies").session(operator))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		int row = html.indexOf("<td>" + NAME_PREFIX + " Flow Co</td>");
		assertThat(row).as("registered company listed").isPositive();
		String rowHtml = html.substring(row, html.indexOf("</tr>", row));
		assertThat(rowHtml).contains("<td>" + REGISTRANT_EMAIL + "</td>").contains("Oczekuje");
	}

}

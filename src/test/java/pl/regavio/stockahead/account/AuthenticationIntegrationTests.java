package pl.regavio.stockahead.account;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.security.CompanyUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers the /setup bootstrap flow (the company-less operator), form login,
 * the principal's company and the /manager/ping role check, all against a
 * real Postgres via Testcontainers (per AGENTS.md). Deliberately not
 * {@code @Transactional}: setup must commit for real so two concurrent
 * submissions can race.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTests {

	private static final String SETUP_TOKEN = "local-only-setup-token";

	private static final String SETUP_PASSWORD = "s3cret-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Company company;

	@BeforeEach
	void setUp() {
		// Checked before cleanUp(), which deletes every company-less account: an
		// operator present here was leaked by another test class.
		assertThat(jdbcTemplate.queryForList("SELECT email FROM accounts WHERE role = 'OPERATOR'", String.class))
			.as("another test class leaked an OPERATOR account; /setup would be closed")
			.isEmpty();
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	/**
	 * Accounts created through {@code POST /setup} are operators, which have no
	 * company; {@link CompanyFixtures#cleanUp()} deletes every company-less
	 * account along with what it created itself.
	 */
	private void cleanUp() {
		companyFixtures.cleanUp();
	}

	@Test
	void setupFormIsAvailableWhenNoOperatorExists() throws Exception {
		mockMvc.perform(get("/setup"))
			.andExpect(status().isOk())
			.andExpect(view().name("setup"));
	}

	@Test
	void setupWithCorrectTokenCreatesActiveOperatorWithoutCompany() throws Exception {
		int companiesBefore = companyCount();

		mockMvc.perform(postSetup(" New-Operator@Example.COM ", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?setup"));

		Account created = accountRepository.findByEmail("new-operator@example.com").orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.OPERATOR);
		assertThat(created.isActive()).isTrue();
		assertThat(created.getCompany()).isNull();
		assertThat(operatorEmails()).containsExactly("new-operator@example.com");
		assertThat(companyCount()).isEqualTo(companiesBefore);
	}

	@Test
	void setupStaysOpenWhileCompaniesAndManagersExistButNoOperator() throws Exception {
		seedAccount("existing-manager@example.com", "irrelevant-password", Role.MANAGER, true);

		mockMvc.perform(get("/setup"))
			.andExpect(status().isOk())
			.andExpect(view().name("setup"));

		mockMvc.perform(postSetup("open-setup@example.com", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?setup"));

		assertThat(operatorEmails()).containsExactly("open-setup@example.com");
	}

	@Test
	void setupFormRedirectsToLoginOnceOperatorExists() throws Exception {
		companyFixtures.operator("existing-operator@example.com", "irrelevant-password", true);

		mockMvc.perform(get("/setup"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login"));
	}

	@Test
	void setupPostCreatesNothingOnceOperatorExists() throws Exception {
		companyFixtures.operator("existing-operator@example.com", "irrelevant-password", true);
		int companiesBefore = companyCount();

		mockMvc.perform(postSetup("closed-setup@example.com", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login"));

		assertThat(accountRepository.findByEmail("closed-setup@example.com")).isEmpty();
		assertThat(operatorEmails()).containsExactly("existing-operator@example.com");
		assertThat(companyCount()).isEqualTo(companiesBefore);
	}

	@Test
	void twoConcurrentSetupsCreateExactlyOneOperator() throws Exception {
		for (int iteration = 0; iteration < 5; iteration++) {
			cleanUp();
			int companiesBefore = companyCount();

			MvcResult[] results = race(
					() -> mockMvc.perform(postSetup("race-first@example.com", SETUP_TOKEN)).andReturn(),
					() -> mockMvc.perform(postSetup("race-second@example.com", SETUP_TOKEN)).andReturn());

			assertThat(results[0].getResponse().getStatus()).as("iteration %d: first status", iteration)
				.isEqualTo(302);
			assertThat(results[1].getResponse().getStatus()).as("iteration %d: second status", iteration)
				.isEqualTo(302);
			List<Account> operators = Stream.of("race-first@example.com", "race-second@example.com")
				.map(accountRepository::findByEmail)
				.flatMap(Optional::stream)
				.toList();
			assertThat(operators).as("iteration %d: operators created", iteration).hasSize(1);
			assertThat(operators.get(0).getRole()).isEqualTo(Role.OPERATOR);
			assertThat(operators.get(0).getCompany()).isNull();
			assertThat(operatorEmails()).as("iteration %d: operators in the database", iteration).hasSize(1);
			assertThat(companyCount()).as("iteration %d: companies created", iteration).isEqualTo(companiesBefore);
		}
	}

	@Test
	void setupWithWrongTokenCreatesNothingAndKeepsEmail() throws Exception {
		assertSetupRejected(postSetup("rejected@example.com", "wrong-token"), "rejected@example.com",
				"Nieprawidłowy token konfiguracyjny.");
	}

	@Test
	void setupWithInvalidEmailCreatesNothingAndKeepsEmail() throws Exception {
		assertSetupRejected(postSetup("not-an-email", SETUP_PASSWORD, SETUP_PASSWORD, SETUP_TOKEN), "not-an-email",
				"Podaj poprawny adres e-mail.");
	}

	@Test
	void setupWithElevenCharacterPasswordCreatesNothingAndKeepsEmail() throws Exception {
		String shortPassword = "a".repeat(11);

		assertSetupRejected(postSetup("short-password@example.com", shortPassword, shortPassword, SETUP_TOKEN),
				"short-password@example.com", "Hasło musi mieć co najmniej 12 znaków.");
	}

	@Test
	void setupWithMismatchedConfirmationCreatesNothingAndKeepsEmail() throws Exception {
		assertSetupRejected(
				postSetup("mismatch@example.com", SETUP_PASSWORD, SETUP_PASSWORD + "-other", SETUP_TOKEN),
				"mismatch@example.com", "Hasła nie są identyczne.");
	}

	@Test
	void setupWithEmailOfExistingAccountCreatesNothingAndKeepsEmail() throws Exception {
		Account manager = seedAccount("taken@example.com", "irrelevant-password", Role.MANAGER, true);

		mockMvc.perform(postSetup(" Taken@Example.com ", SETUP_TOKEN))
			.andExpect(status().isOk())
			.andExpect(view().name("setup"))
			.andExpect(model().attribute("email", " Taken@Example.com "))
			.andExpect(model().attribute("error", "Konto o tym adresie e-mail już istnieje."));

		assertThat(operatorEmails()).isEmpty();
		Account unchanged = accountRepository.findByEmail("taken@example.com").orElseThrow();
		assertThat(unchanged.getId()).isEqualTo(manager.getId());
		assertThat(unchanged.getRole()).isEqualTo(Role.MANAGER);
	}

	@Test
	void operatorCanLogInAfterSetup() throws Exception {
		mockMvc.perform(postSetup("login-operator@example.com", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?setup"));

		MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin()
				.user(" Login-Operator@Example.COM ")
				.password(SETUP_PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();

		SecurityContext context = (SecurityContext) session
			.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		CompanyUser principal = (CompanyUser) context.getAuthentication().getPrincipal();
		assertThat(principal.companyId()).isNull();
		assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_OPERATOR");
	}

	@Test
	void loginWithCorrectCredentialsAuthenticatesSession() throws Exception {
		seedAccount("manager@example.com", "correct-password", Role.MANAGER, true);

		MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin()
				.user("manager@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk());
	}

	@Test
	void loggedInPrincipalCarriesTheAccountsCompany() throws Exception {
		Account manager = seedAccount("manager@example.com", "correct-password", Role.MANAGER, true);

		MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin()
				.user("manager@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();

		SecurityContext context = (SecurityContext) session
			.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(CompanyUser.class);
		CompanyUser principal = (CompanyUser) context.getAuthentication().getPrincipal();
		assertThat(principal.companyId()).isEqualTo(company().getId());
		assertThat(principal.companyId()).isEqualTo(manager.getCompany().getId());
	}

	@Test
	void loginAcceptsDifferentCaseAndSurroundingSpaces() throws Exception {
		seedAccount("manager-case@example.com", "correct-password", Role.MANAGER, true);

		MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin()
				.user(" Manager-Case@Example.COM ")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk());
	}

	@Test
	void loginWithWrongPasswordRedirectsToLoginError() throws Exception {
		seedAccount("manager2@example.com", "correct-password", Role.MANAGER, true);

		mockMvc.perform(formLogin()
				.user("manager2@example.com")
				.password("wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"));
	}

	@Test
	void inactiveAccountCannotLogIn() throws Exception {
		seedAccount("inactive@example.com", "correct-password", Role.TECHNICIAN, false);

		mockMvc.perform(formLogin()
				.user("inactive@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"));
	}

	@Test
	void releasedAccountCannotLogInWithTheCorrectPasswordAndGetsNoSession() throws Exception {
		Company rejected = companyFixtures.company("AuthenticationIntegrationTests Rejected Co", CompanyStatus.REJECTED);
		companyFixtures.releasedAccount(rejected, "released@example.com", "correct-password", Role.MANAGER);

		MvcResult result = mockMvc.perform(formLogin()
				.user("released@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"))
			.andReturn();

		// The fixture is also inactive, so this pins the generic outcome, not the
		// email_released_at filter itself; that filter is pinned by
		// liveAccountSharingAReleasedAccountsEmailLogsInAsItself.
		// The failure handler keeps a session only to carry the login error.
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
		assertThat(session).as("session holding the login error").isNotNull();
		assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
		mockMvc.perform(get("/").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
	}

	@Test
	void liveAccountSharingAReleasedAccountsEmailLogsInAsItself() throws Exception {
		Company rejected = companyFixtures.company("AuthenticationIntegrationTests Rejected Co", CompanyStatus.REJECTED);
		Account released = companyFixtures.releasedAccount(rejected, "reused@example.com", "old-password-value",
				Role.MANAGER);
		Account live = seedAccount("reused@example.com", "new-password-value", Role.MANAGER, true);

		MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin()
				.user("reused@example.com")
				.password("new-password-value"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"))
			.andReturn().getRequest().getSession();

		SecurityContext context = (SecurityContext) session
			.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		CompanyUser principal = (CompanyUser) context.getAuthentication().getPrincipal();
		assertThat(principal.accountId()).isEqualTo(live.getId()).isNotEqualTo(released.getId());
		assertThat(principal.companyId()).isEqualTo(company().getId()).isNotEqualTo(rejected.getId());
		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk());

		mockMvc.perform(formLogin()
				.user("reused@example.com")
				.password("old-password-value"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?error"));
	}

	@Test
	void managerPingAllowsManagerAndForbidsTechnician() throws Exception {
		seedAccount("ping-manager@example.com", "correct-password", Role.MANAGER, true);
		seedAccount("ping-technician@example.com", "correct-password", Role.TECHNICIAN, true);

		MockHttpSession managerSession = (MockHttpSession) mockMvc.perform(formLogin()
				.user("ping-manager@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();

		mockMvc.perform(get("/manager/ping").session(managerSession))
			.andExpect(status().isOk());

		MockHttpSession technicianSession = (MockHttpSession) mockMvc.perform(formLogin()
				.user("ping-technician@example.com")
				.password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();

		mockMvc.perform(get("/manager/ping").session(technicianSession))
			.andExpect(status().isForbidden());
	}

	private RequestBuilder postSetup(String email, String token) {
		return postSetup(email, SETUP_PASSWORD, SETUP_PASSWORD, token);
	}

	private RequestBuilder postSetup(String email, String password, String confirmPassword, String token) {
		return post("/setup")
			.with(csrf())
			.param("email", email)
			.param("password", password)
			.param("confirmPassword", confirmPassword)
			.param("token", token);
	}

	/**
	 * Performs a setup the form must refuse: it re-renders with the email kept
	 * and the given error, and creates neither an account nor a company.
	 */
	private void assertSetupRejected(RequestBuilder request, String email, String error) throws Exception {
		int companiesBefore = companyCount();

		mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name("setup"))
			.andExpect(model().attribute("email", email))
			.andExpect(model().attribute("error", error));

		assertThat(accountRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))).isEmpty();
		assertThat(operatorEmails()).isEmpty();
		assertThat(companyCount()).isEqualTo(companiesBefore);
	}

	private List<String> operatorEmails() {
		return jdbcTemplate.queryForList("SELECT email FROM accounts WHERE role = 'OPERATOR'", String.class);
	}

	private int companyCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Integer.class);
	}

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("AuthenticationIntegrationTests Co");
		}
		return company;
	}

	private Account seedAccount(String email, String rawPassword, Role role, boolean active) {
		return companyFixtures.account(company(), email, rawPassword, role, active);
	}

	/** Runs both requests on two threads released together, returning their results in order. */
	private MvcResult[] race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<MvcResult> firstFuture = executor.submit(() -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				return first.call();
			});
			Future<MvcResult> secondFuture = executor.submit(() -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				return second.call();
			});
			return new MvcResult[] { firstFuture.get(20, TimeUnit.SECONDS), secondFuture.get(20, TimeUnit.SECONDS) };
		}
		finally {
			executor.shutdownNow();
		}
	}

}

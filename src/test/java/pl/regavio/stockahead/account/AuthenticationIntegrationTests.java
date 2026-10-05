package pl.regavio.stockahead.account;

import java.util.List;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.security.CompanyUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers the /setup bootstrap flow (company + first manager), form login,
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

	/**
	 * Accounts created through {@code POST /setup}, which {@link CompanyFixtures}
	 * does not track; cleanup deletes them and then their companies.
	 */
	private static final List<String> SETUP_EMAILS = List.of("new-manager@example.com", "rejected@example.com",
			"blank-company@example.com", "long-company@example.com", "closed-setup@example.com",
			"race-first@example.com", "race-second@example.com");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyRepository companyRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Company company;

	@BeforeEach
	void setUp() {
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		for (String email : SETUP_EMAILS) {
			List<Long> companyIds = jdbcTemplate.queryForList(
					"DELETE FROM accounts WHERE email = ? RETURNING company_id", Long.class, email);
			companyIds.forEach(companyId -> jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId));
		}
		companyFixtures.cleanUp();
	}

	@Test
	void setupFormIsAvailableWhenNoManagerExists() throws Exception {
		mockMvc.perform(get("/setup"))
			.andExpect(status().isOk());
	}

	@Test
	void setupWithCorrectTokenCreatesActiveManagerInNewActiveCompany() throws Exception {
		mockMvc.perform(postSetup(" Zakład Testowy ", " New-Manager@Example.COM ", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?setup"));

		Account created = accountRepository.findByEmail("new-manager@example.com").orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.MANAGER);
		assertThat(created.isActive()).isTrue();
		Company createdCompany = companyRepository.findById(created.getCompany().getId()).orElseThrow();
		assertThat(createdCompany.getName()).isEqualTo("Zakład Testowy");
		assertThat(createdCompany.getStatus()).isEqualTo(CompanyStatus.ACTIVE);
		assertThat(companiesNamed("Zakład Testowy")).isEqualTo(1);
	}

	@Test
	void setupWithBlankCompanyNameCreatesNothingAndRerendersForm() throws Exception {
		mockMvc.perform(postSetup("   ", "blank-company@example.com", SETUP_TOKEN))
			.andExpect(status().isOk())
			.andExpect(view().name("setup"))
			.andExpect(model().attribute("email", "blank-company@example.com"))
			.andExpect(model().attribute("error", "Podaj nazwę firmy."));

		assertThat(accountRepository.findByEmail("blank-company@example.com")).isEmpty();
	}

	@Test
	void setupWithTooLongCompanyNameCreatesNothingAndKeepsInput() throws Exception {
		String longName = "F".repeat(256);

		mockMvc.perform(postSetup(longName, "long-company@example.com", SETUP_TOKEN))
			.andExpect(status().isOk())
			.andExpect(view().name("setup"))
			.andExpect(model().attribute("email", "long-company@example.com"))
			.andExpect(model().attribute("companyName", longName))
			.andExpect(model().attribute("error", "Nazwa firmy może mieć maksymalnie 255 znaków."));

		assertThat(accountRepository.findByEmail("long-company@example.com")).isEmpty();
		assertThat(companiesNamed(longName)).isZero();
	}

	@Test
	void setupFormRedirectsToLoginOnceManagerExists() throws Exception {
		seedAccount("existing-manager@example.com", "irrelevant-password", Role.MANAGER, true);

		mockMvc.perform(get("/setup"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login"));
	}

	@Test
	void setupPostCreatesNothingOnceManagerExists() throws Exception {
		seedAccount("existing-manager@example.com", "irrelevant-password", Role.MANAGER, true);

		mockMvc.perform(postSetup("Closed Setup Company", "closed-setup@example.com", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login"));

		assertThat(accountRepository.findByEmail("closed-setup@example.com")).isEmpty();
		assertThat(companiesNamed("Closed Setup Company")).isZero();
	}

	@Test
	void twoConcurrentSetupsCreateExactlyOneCompanyAndOneManager() throws Exception {
		for (int iteration = 0; iteration < 5; iteration++) {
			cleanUp();

			MvcResult[] results = race(
					() -> mockMvc.perform(postSetup("Race First Company", "race-first@example.com", SETUP_TOKEN))
						.andReturn(),
					() -> mockMvc.perform(postSetup("Race Second Company", "race-second@example.com", SETUP_TOKEN))
						.andReturn());

			assertThat(results[0].getResponse().getStatus()).as("iteration %d: first status", iteration)
				.isEqualTo(302);
			assertThat(results[1].getResponse().getStatus()).as("iteration %d: second status", iteration)
				.isEqualTo(302);
			List<Account> managers = Stream.of("race-first@example.com", "race-second@example.com")
				.map(accountRepository::findByEmail)
				.flatMap(Optional::stream)
				.toList();
			assertThat(managers).as("iteration %d: managers created", iteration).hasSize(1);
			assertThat(managers.get(0).getRole()).isEqualTo(Role.MANAGER);
			assertThat(managers.get(0).getCompany()).isNotNull();
			assertThat(companiesNamed("Race First Company") + companiesNamed("Race Second Company"))
				.as("iteration %d: companies created", iteration)
				.isEqualTo(1);
		}
	}

	@Test
	void setupWithWrongTokenDoesNotCreateAccount() throws Exception {
		mockMvc.perform(postSetup("Rejected Company", "rejected@example.com", "wrong-token"))
			.andExpect(status().isOk());

		assertThat(accountRepository.findByEmail("rejected@example.com")).isEmpty();
		assertThat(companiesNamed("Rejected Company")).isZero();
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

	private RequestBuilder postSetup(String companyName, String email, String token) {
		return post("/setup")
			.with(csrf())
			.param("companyName", companyName)
			.param("email", email)
			.param("password", SETUP_PASSWORD)
			.param("confirmPassword", SETUP_PASSWORD)
			.param("token", token);
	}

	private int companiesNamed(String name) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM companies WHERE name = ?", Integer.class, name);
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

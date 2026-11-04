package pl.regavio.stockahead.account;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class CompanyAccountIntegrationTests {

	private static final String MANAGER_EMAIL = "company-accounts-manager@company-accounts.example";

	private static final String TECHNICIAN_EMAIL = "existing@company-accounts.example";

	private static final String PASSWORD = "correct-password";

	private static final String OTHER_MANAGER_EMAIL = "other-manager@company-accounts.example";

	private static final String SECOND_MANAGER_EMAIL = "second-manager@company-accounts.example";

	private static final String OTHER_SECOND_MANAGER_EMAIL = "other-second-manager@company-accounts.example";

	private static final String LAST_ACTIVE_MANAGER_ERROR = "Nie można dezaktywować ani zmienić roli ostatniego aktywnego kierownika firmy.";

	private static final String OTHER_TECHNICIAN_EMAIL = "other-technician@company-accounts.example";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company company;

	private Company otherCompany;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void setUp() {
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		accountRepository.findAll().stream()
			.filter(account -> account.getEmail().endsWith("@company-accounts.example"))
			.forEach(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("CompanyAccountIntegrationTests Co");
		}
		return company;
	}

	private Company otherCompany() {
		if (otherCompany == null) {
			otherCompany = companyFixtures.company("CompanyAccountIntegrationTests Other Co");
		}
		return otherCompany;
	}

	private Account seedAccount(String email, Role role, boolean active) {
		return companyFixtures.account(company(), email, PASSWORD, role, active);
	}

	private Account seedOtherCompanyAccount(String email, Role role, boolean active) {
		return companyFixtures.account(otherCompany(), email, PASSWORD, role, active);
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	@Test
	void managerCreatesTechnicianWithCanonicalEmailAndBcryptPasswordWhoCanLogIn() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", " New@Company-Accounts.Example ")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"));

		Account created = accountRepository.findByEmail("new@company-accounts.example").orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.TECHNICIAN);
		assertThat(created.isActive()).isTrue();
		assertThat(created.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
		mockMvc.perform(formLogin().user(created.getEmail()).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/"));
	}

	@Test
	void formRejectsInvalidEmailShortPasswordAndMismatchedConfirmation() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		for (String invalidEmail : new String[] { " ", "bad-address", "a".repeat(250) + "@example.com" }) {
			mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
					.param("role", "TECHNICIAN")
					.param("email", invalidEmail)
					.param("password", PASSWORD)
					.param("confirmPassword", PASSWORD))
				.andExpect(status().isOk());
		}
		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "short@company-accounts.example")
				.param("password", "short")
				.param("confirmPassword", "short"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("co najmniej 12 znaków")));
		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "mismatch@company-accounts.example")
				.param("password", PASSWORD)
				.param("confirmPassword", "different-password"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Hasła nie są identyczne")));

		assertThat(accountRepository.findByEmail("short@company-accounts.example")).isEmpty();
		assertThat(accountRepository.findByEmail("mismatch@company-accounts.example")).isEmpty();
	}

	@Test
	void canonicalDuplicateOfInactiveTechnicianOrManagerRendersErrorWithoutSecondAccount() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, false);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		for (String duplicate : new String[] { " EXISTING@Company-Accounts.Example ",
				" COMPANY-ACCOUNTS-MANAGER@Company-Accounts.Example " }) {
			mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
					.param("role", "TECHNICIAN")
					.param("email", duplicate)
					.param("password", PASSWORD)
					.param("confirmPassword", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Konto o tym adresie e-mail już istnieje")))
				.andExpect(content().string(containsString(duplicate.strip())))
				.andExpect(content().string(not(containsString(PASSWORD))));
		}
		assertThat(accountRepository.findAll().stream()
			.filter(account -> account.getEmail().endsWith("@company-accounts.example")))
			.hasSize(2);
		assertThat(accountRepository.findById(manager.getId())).isPresent();
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test
	void listShowsBothRolesAndStatesAndDashboardLinkIsManagerOnly() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		seedAccount("inactive@company-accounts.example", Role.TECHNICIAN, false);
		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/manager/accounts").session(managerSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<td>" + TECHNICIAN_EMAIL + "</td>")))
			.andExpect(content().string(containsString("inactive@company-accounts.example")))
			.andExpect(content().string(containsString("Dezaktywuj")))
			.andExpect(content().string(containsString("Reaktywuj")))
			.andExpect(content().string(containsString("<td>" + MANAGER_EMAIL + "</td>")))
			.andExpect(content().string(containsString("<th>Rola</th>")))
			.andExpect(content().string(containsString("<td>Kierownik</td>")))
			.andExpect(content().string(containsString("<td>Technik</td>")))
			.andExpect(content().string(containsString("_csrf")));
		mockMvc.perform(get("/").session(managerSession))
			.andExpect(content().string(containsString("/manager/accounts")))
			.andExpect(content().string(containsString("/parts")));
		mockMvc.perform(get("/").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(content().string(not(containsString("/manager/accounts"))));
	}

	@Test
	void technicianGets403OnEveryAccountsRoute() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/manager/accounts").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(get("/manager/accounts/new").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "unauthorized@company-accounts.example")
				.param("password", PASSWORD).param("confirmPassword", PASSWORD))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/accounts/" + technician.getId() + "/deactivate")
				.session(session).with(csrf())).andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/accounts/" + technician.getId() + "/reactivate")
				.session(session).with(csrf())).andExpect(status().isForbidden());
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isTrue();
		assertThat(accountRepository.findByEmail("unauthorized@company-accounts.example")).isEmpty();
	}

	@Test
	void activityChangesAreIdempotentAndPreserveIdentityAndHistory() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		Long id = technician.getId();
		Account persisted = accountRepository.findById(id).orElseThrow();
		String originalHash = persisted.getPasswordHash();
		Instant createdAt = persisted.getCreatedAt();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/manager/accounts/" + id + "/deactivate")
					.session(session).with(csrf()))
				.andExpect(status().is3xxRedirection());
			assertPreserved(id, false, originalHash, createdAt);
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/manager/accounts/" + id + "/reactivate")
					.session(session).with(csrf()))
				.andExpect(status().is3xxRedirection());
			assertPreserved(id, true, originalHash, createdAt);
		}
	}

	@Test
	void anotherManagersIdIsManageableAndMissingIdReturns404() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account secondManager = seedAccount(SECOND_MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		long missingId = Long.MAX_VALUE;

		mockMvc.perform(post("/manager/accounts/" + secondManager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"))
			.andExpect(flash().attributeCount(0));
		assertThat(accountRepository.findById(secondManager.getId()).orElseThrow().isActive()).isFalse();
		mockMvc.perform(post("/manager/accounts/" + secondManager.getId() + "/reactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"));
		assertThat(accountRepository.findById(secondManager.getId()).orElseThrow().isActive()).isTrue();

		mockMvc.perform(post("/manager/accounts/" + missingId + "/deactivate")
				.session(session).with(csrf())).andExpect(status().isNotFound());
		mockMvc.perform(post("/manager/accounts/" + missingId + "/reactivate")
				.session(session).with(csrf())).andExpect(status().isNotFound());
		assertThat(accountRepository.findById(missingId)).isEmpty();
	}

	@Test
	void otherCompanysManagerDoesNotSeeThisCompanysAccounts() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		seedAccount("inactive@company-accounts.example", Role.TECHNICIAN, false);
		seedOtherCompanyAccount(OTHER_MANAGER_EMAIL, Role.MANAGER, true);
		seedOtherCompanyAccount(OTHER_TECHNICIAN_EMAIL, Role.TECHNICIAN, true);

		mockMvc.perform(get("/manager/accounts").session(loginAs(OTHER_MANAGER_EMAIL)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<td>" + OTHER_TECHNICIAN_EMAIL + "</td>")))
			.andExpect(content().string(containsString("<td>" + OTHER_MANAGER_EMAIL + "</td>")))
			.andExpect(content().string(not(containsString(TECHNICIAN_EMAIL))))
			.andExpect(content().string(not(containsString(MANAGER_EMAIL))))
			.andExpect(content().string(not(containsString("inactive@company-accounts.example"))));
		mockMvc.perform(get("/manager/accounts").session(loginAs(MANAGER_EMAIL)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<td>" + TECHNICIAN_EMAIL + "</td>")))
			.andExpect(content().string(not(containsString(OTHER_TECHNICIAN_EMAIL))))
			.andExpect(content().string(not(containsString(OTHER_MANAGER_EMAIL))));
	}

	@Test
	void otherCompanysManagerGets404OnDeactivateAndReactivateWithoutChangingAccounts() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account inactiveManager = seedAccount(SECOND_MANAGER_EMAIL, Role.MANAGER, false);
		Account activeTechnician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		Account inactiveTechnician = seedAccount("inactive@company-accounts.example", Role.TECHNICIAN, false);
		seedOtherCompanyAccount(OTHER_MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession otherSession = loginAs(OTHER_MANAGER_EMAIL);

		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/manager/accounts/" + activeTechnician.getId() + "/deactivate")
					.session(otherSession).with(csrf()))
				.andExpect(status().isNotFound());
			mockMvc.perform(post("/manager/accounts/" + inactiveTechnician.getId() + "/reactivate")
					.session(otherSession).with(csrf()))
				.andExpect(status().isNotFound());
			mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
					.session(otherSession).with(csrf()))
				.andExpect(status().isNotFound());
			mockMvc.perform(post("/manager/accounts/" + inactiveManager.getId() + "/reactivate")
					.session(otherSession).with(csrf()))
				.andExpect(status().isNotFound());
		}

		assertThat(accountRepository.findById(activeTechnician.getId()).orElseThrow().isActive()).isTrue();
		assertThat(accountRepository.findById(inactiveTechnician.getId()).orElseThrow().isActive()).isFalse();
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
		assertThat(accountRepository.findById(inactiveManager.getId()).orElseThrow().isActive()).isFalse();
		mockMvc.perform(get("/parts").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk());
	}

	@Test
	void technicianCreatedByOtherCompanysManagerBelongsToThatCompany() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedOtherCompanyAccount(OTHER_MANAGER_EMAIL, Role.MANAGER, true);

		mockMvc.perform(post("/manager/accounts").session(loginAs(OTHER_MANAGER_EMAIL)).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "created-by-other@company-accounts.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"));

		Account created = accountRepository.findByEmail("created-by-other@company-accounts.example").orElseThrow();
		assertThat(created.getCompany().getId()).isEqualTo(otherCompany().getId());
		mockMvc.perform(get("/manager/accounts").session(loginAs(MANAGER_EMAIL)))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("created-by-other@company-accounts.example"))));
	}

	@Test
	void deactivationEndsExistingTechnicianSessionsBeforeGetOrPostAndReactivationRequiresNewLogin() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);
		MockHttpSession getSession = loginAs(TECHNICIAN_EMAIL);
		MockHttpSession postSession = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts").session(getSession)).andExpect(status().isOk());
		mockMvc.perform(get("/manager/accounts").session(managerSession)).andExpect(status().isOk());

		mockMvc.perform(post("/manager/accounts/" + technician.getId() + "/deactivate")
				.session(managerSession).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();

		mockMvc.perform(get("/parts").session(getSession))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		mockMvc.perform(post("/manager/accounts").session(postSession).with(csrf())
				.param("role", "TECHNICIAN")
				.param("email", "blocked@company-accounts.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		assertThat(getSession.isInvalid()).isTrue();
		assertThat(postSession.isInvalid()).isTrue();
		assertThat(accountRepository.findByEmail("blocked@company-accounts.example")).isEmpty();
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();

		mockMvc.perform(post("/manager/accounts/" + technician.getId() + "/reactivate")
				.session(managerSession).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(getSession.isInvalid()).isTrue();
		assertThat(postSession.isInvalid()).isTrue();
		mockMvc.perform(get("/parts").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk());
	}

	@Test
	void deactivationMessageAndPublicEndpointsRemainAvailable() throws Exception {
		mockMvc.perform(get("/login?deactivated"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Twoje konto jest nieaktywne")));
		mockMvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk());

		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		mockMvc.perform(get("/manager/accounts").session(loginAs(MANAGER_EMAIL)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/parts").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk());
	}

	@Test
	void missingAccountAlsoEndsItsExistingSession() throws Exception {
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);
		accountRepository.deleteById(technician.getId());
		accountRepository.flush();

		mockMvc.perform(get("/parts").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void managerCreatesManagerAccountWhoCanLogInAndReachManagerOnlyRoute() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/manager/accounts/new").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<select id=\"role\" name=\"role\" required>")))
			.andExpect(content().string(containsString("<option value=\"TECHNICIAN\" selected=\"selected\">Technik</option>")));
		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "MANAGER")
				.param("email", SECOND_MANAGER_EMAIL)
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"));

		Account created = accountRepository.findByEmail(SECOND_MANAGER_EMAIL).orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.MANAGER);
		assertThat(created.isActive()).isTrue();
		assertThat(created.getCompany().getId()).isEqualTo(company().getId());
		MockHttpSession createdSession = loginAs(SECOND_MANAGER_EMAIL);
		mockMvc.perform(get("/manager/ping").session(createdSession)).andExpect(status().isOk());
		mockMvc.perform(get("/manager/accounts").session(createdSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<td>" + MANAGER_EMAIL + "</td>")));
	}

	@Test
	void invalidRoleIsRejectedWithoutCreatingAccountAndValidRoleIsEchoedOnError() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		for (String invalidRole : new String[] { "", "OPERATOR", "manager", "ADMIN" }) {
			mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
					.param("role", invalidRole)
					.param("email", "bad-role@company-accounts.example")
					.param("password", PASSWORD)
					.param("confirmPassword", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Wybierz rolę konta.")))
				.andExpect(content().string(containsString("bad-role@company-accounts.example")));
		}
		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("email", "no-role@company-accounts.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Wybierz rolę konta.")));
		assertThat(accountRepository.findByEmail("bad-role@company-accounts.example")).isEmpty();
		assertThat(accountRepository.findByEmail("no-role@company-accounts.example")).isEmpty();

		mockMvc.perform(post("/manager/accounts").session(session).with(csrf())
				.param("role", "MANAGER")
				.param("email", "echo@company-accounts.example")
				.param("password", PASSWORD)
				.param("confirmPassword", "different-password"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<option value=\"MANAGER\" selected=\"selected\">Kierownik</option>")));
		assertThat(accountRepository.findByEmail("echo@company-accounts.example")).isEmpty();
	}

	@Test
	void soleActiveManagerCannotDeactivateSelfAndSeesError() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		MvcResult result = mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"))
			.andExpect(flash().attribute("error", LAST_ACTIVE_MANAGER_ERROR))
			.andReturn();

		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
		mockMvc.perform(get("/manager/accounts").session(session).flashAttrs(result.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("alert-error")))
			.andExpect(content().string(containsString(LAST_ACTIVE_MANAGER_ERROR)));
		mockMvc.perform(get("/manager/ping").session(session)).andExpect(status().isOk());
	}

	@Test
	void managerDeactivatesTheOtherManagerThenRemainingOneIsRefused() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account secondManager = seedAccount(SECOND_MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/manager/accounts/" + secondManager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attributeCount(0));
		assertThat(accountRepository.findById(secondManager.getId()).orElseThrow().isActive()).isFalse();

		mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", LAST_ACTIVE_MANAGER_ERROR));
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
	}

	@Test
	void selfDeactivationWithAnotherActiveManagerSucceedsAndEndsCallersSession() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account secondManager = seedAccount(SECOND_MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/accounts"))
			.andExpect(flash().attributeCount(0));
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isFalse();
		assertThat(accountRepository.findById(secondManager.getId()).orElseThrow().isActive()).isTrue();

		mockMvc.perform(get("/manager/accounts").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void inactiveManagerDoesNotCountTowardsActiveManagers() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedAccount(SECOND_MANAGER_EMAIL, Role.MANAGER, false);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
				.session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", LAST_ACTIVE_MANAGER_ERROR));
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
	}

	@Test
	void lastActiveManagerRuleCountsOnlyTheCallersCompany() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account otherManager = seedOtherCompanyAccount(OTHER_MANAGER_EMAIL, Role.MANAGER, true);
		Account otherSecondManager = seedOtherCompanyAccount(OTHER_SECOND_MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession otherSession = loginAs(OTHER_MANAGER_EMAIL);

		// Company A has a single active manager; that never blocks company B.
		mockMvc.perform(post("/manager/accounts/" + otherSecondManager.getId() + "/deactivate")
				.session(otherSession).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attributeCount(0));
		assertThat(accountRepository.findById(otherSecondManager.getId()).orElseThrow().isActive()).isFalse();

		// Company B's remaining manager is its last one; A's active manager does not count for B.
		mockMvc.perform(post("/manager/accounts/" + otherManager.getId() + "/deactivate")
				.session(otherSession).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", LAST_ACTIVE_MANAGER_ERROR));
		assertThat(accountRepository.findById(otherManager.getId()).orElseThrow().isActive()).isTrue();

		// And B's active manager does not count for A.
		mockMvc.perform(post("/manager/accounts/" + manager.getId() + "/deactivate")
				.session(loginAs(MANAGER_EMAIL)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", LAST_ACTIVE_MANAGER_ERROR));
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
	}

	private void assertPreserved(Long id, boolean active, String hash, Instant createdAt) {
		Account reloaded = accountRepository.findById(id).orElseThrow();
		assertThat(reloaded.getId()).isEqualTo(id);
		assertThat(reloaded.getEmail()).isEqualTo(TECHNICIAN_EMAIL);
		assertThat(reloaded.getPasswordHash()).isEqualTo(hash);
		assertThat(reloaded.getRole()).isEqualTo(Role.TECHNICIAN);
		assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
		assertThat(reloaded.isActive()).isEqualTo(active);
	}

}

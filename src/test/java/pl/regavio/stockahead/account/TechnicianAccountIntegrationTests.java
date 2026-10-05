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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class TechnicianAccountIntegrationTests {

	private static final String MANAGER_EMAIL = "technician-phase2-manager@technician-phase2.example";

	private static final String TECHNICIAN_EMAIL = "existing@technician-phase2.example";

	private static final String PASSWORD = "correct-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company company;

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
			.filter(account -> account.getEmail().endsWith("@technician-phase2.example"))
			.forEach(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("TechnicianAccountIntegrationTests Co");
		}
		return company;
	}

	private Account seedAccount(String email, Role role, boolean active) {
		return companyFixtures.account(company(), email, PASSWORD, role, active);
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

		mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
				.param("email", " New@Technician-Phase2.Example ")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/manager/technicians"));

		Account created = accountRepository.findByEmail("new@technician-phase2.example").orElseThrow();
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
			mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
					.param("email", invalidEmail)
					.param("password", PASSWORD)
					.param("confirmPassword", PASSWORD))
				.andExpect(status().isOk());
		}
		mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
				.param("email", "short@technician-phase2.example")
				.param("password", "short")
				.param("confirmPassword", "short"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("co najmniej 12 znaków")));
		mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
				.param("email", "mismatch@technician-phase2.example")
				.param("password", PASSWORD)
				.param("confirmPassword", "different-password"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Hasła nie są identyczne")));

		assertThat(accountRepository.findByEmail("short@technician-phase2.example")).isEmpty();
		assertThat(accountRepository.findByEmail("mismatch@technician-phase2.example")).isEmpty();
	}

	@Test
	void canonicalDuplicateOfInactiveTechnicianOrManagerRendersErrorWithoutSecondAccount() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, false);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		for (String duplicate : new String[] { " EXISTING@Technician-Phase2.Example ",
				" TECHNICIAN-PHASE2-MANAGER@Technician-Phase2.Example " }) {
			mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
					.param("email", duplicate)
					.param("password", PASSWORD)
					.param("confirmPassword", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Konto o tym adresie e-mail już istnieje")))
				.andExpect(content().string(containsString(duplicate.strip())))
				.andExpect(content().string(not(containsString(PASSWORD))));
		}
		assertThat(accountRepository.findAll().stream()
			.filter(account -> account.getEmail().endsWith("@technician-phase2.example")))
			.hasSize(2);
		assertThat(accountRepository.findById(manager.getId())).isPresent();
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test
	void listShowsBothStatesAndDashboardLinkIsManagerOnly() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		seedAccount("inactive@technician-phase2.example", Role.TECHNICIAN, false);
		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/manager/technicians").session(managerSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<td>" + TECHNICIAN_EMAIL + "</td>")))
			.andExpect(content().string(containsString("inactive@technician-phase2.example")))
			.andExpect(content().string(containsString("Dezaktywuj")))
			.andExpect(content().string(containsString("Reaktywuj")))
			.andExpect(content().string(not(containsString("<td>" + MANAGER_EMAIL + "</td>"))))
			.andExpect(content().string(containsString("_csrf")));
		mockMvc.perform(get("/").session(managerSession))
			.andExpect(content().string(containsString("/manager/technicians")))
			.andExpect(content().string(containsString("/parts")));
		mockMvc.perform(get("/").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(content().string(not(containsString("/manager/technicians"))));
	}

	@Test
	void technicianGets403OnEveryManagerOnlyTechnicianRoute() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/manager/technicians").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(get("/manager/technicians/new").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/technicians").session(session).with(csrf())
				.param("email", "unauthorized@technician-phase2.example")
				.param("password", PASSWORD).param("confirmPassword", PASSWORD))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/technicians/" + technician.getId() + "/deactivate")
				.session(session).with(csrf())).andExpect(status().isForbidden());
		mockMvc.perform(post("/manager/technicians/" + technician.getId() + "/reactivate")
				.session(session).with(csrf())).andExpect(status().isForbidden());
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isTrue();
		assertThat(accountRepository.findByEmail("unauthorized@technician-phase2.example")).isEmpty();
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
			mockMvc.perform(post("/manager/technicians/" + id + "/deactivate")
					.session(session).with(csrf()))
				.andExpect(status().is3xxRedirection());
			assertPreserved(id, false, originalHash, createdAt);
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/manager/technicians/" + id + "/reactivate")
					.session(session).with(csrf()))
				.andExpect(status().is3xxRedirection());
			assertPreserved(id, true, originalHash, createdAt);
		}
	}

	@Test
	void managerIdAndMissingIdBothReturn404WithoutChangingAccounts() throws Exception {
		Account manager = seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		long missingId = Long.MAX_VALUE;

		for (long id : new long[] { manager.getId(), missingId }) {
			mockMvc.perform(post("/manager/technicians/" + id + "/deactivate")
					.session(session).with(csrf())).andExpect(status().isNotFound());
			mockMvc.perform(post("/manager/technicians/" + id + "/reactivate")
					.session(session).with(csrf())).andExpect(status().isNotFound());
		}
		assertThat(accountRepository.findById(manager.getId()).orElseThrow().isActive()).isTrue();
		assertThat(accountRepository.findById(missingId)).isEmpty();
	}

	@Test
	void deactivationEndsExistingTechnicianSessionsBeforeGetOrPostAndReactivationRequiresNewLogin() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER, true);
		Account technician = seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN, true);
		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);
		MockHttpSession getSession = loginAs(TECHNICIAN_EMAIL);
		MockHttpSession postSession = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts").session(getSession)).andExpect(status().isOk());
		mockMvc.perform(get("/manager/technicians").session(managerSession)).andExpect(status().isOk());

		mockMvc.perform(post("/manager/technicians/" + technician.getId() + "/deactivate")
				.session(managerSession).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();

		mockMvc.perform(get("/parts").session(getSession))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		mockMvc.perform(post("/manager/technicians").session(postSession).with(csrf())
				.param("email", "blocked@technician-phase2.example")
				.param("password", PASSWORD)
				.param("confirmPassword", PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?deactivated"));
		assertThat(getSession.isInvalid()).isTrue();
		assertThat(postSession.isInvalid()).isTrue();
		assertThat(accountRepository.findByEmail("blocked@technician-phase2.example")).isEmpty();
		assertThat(accountRepository.findById(technician.getId()).orElseThrow().isActive()).isFalse();

		mockMvc.perform(post("/manager/technicians/" + technician.getId() + "/reactivate")
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
		mockMvc.perform(get("/manager/technicians").session(loginAs(MANAGER_EMAIL)))
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

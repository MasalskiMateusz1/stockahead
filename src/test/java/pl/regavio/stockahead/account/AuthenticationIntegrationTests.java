package pl.regavio.stockahead.account;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the automated Success Criteria from Phases 3 and 4: the /setup
 * bootstrap flow, form login, and the /manager/ping role check, all against
 * a real Postgres via Testcontainers (per AGENTS.md).
 *
 * <p>Uses a local {@link PostgresTestcontainersConfiguration} instead of the
 * shared {@code pl.regavio.stockahead.TestcontainersConfiguration} because
 * that class is package-private and lives in a different package
 * ({@code pl.regavio.stockahead}), so it is not visible from here
 * ({@code pl.regavio.stockahead.account}). Same container image/pattern.
 */
@Import(AuthenticationIntegrationTests.PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationIntegrationTests {

	private static final String SETUP_TOKEN = "local-only-setup-token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void setupFormIsAvailableWhenNoManagerExists() throws Exception {
		mockMvc.perform(get("/setup"))
			.andExpect(status().isOk());
	}

	@Test
	void setupWithCorrectTokenCreatesActiveManagerAccount() throws Exception {
		mockMvc.perform(post("/setup")
				.with(csrf())
				.param("email", "new-manager@example.com")
				.param("password", "s3cret-password")
				.param("confirmPassword", "s3cret-password")
				.param("token", SETUP_TOKEN))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login?setup"));

		Account created = accountRepository.findByEmail("new-manager@example.com").orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.MANAGER);
		assertThat(created.isActive()).isTrue();
	}

	@Test
	void setupFormRedirectsToLoginOnceManagerExists() throws Exception {
		seedAccount("existing-manager@example.com", "irrelevant-password", Role.MANAGER, true);

		mockMvc.perform(get("/setup"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/login"));
	}

	@Test
	void setupWithWrongTokenDoesNotCreateAccount() throws Exception {
		mockMvc.perform(post("/setup")
				.with(csrf())
				.param("email", "rejected@example.com")
				.param("password", "s3cret-password")
				.param("confirmPassword", "s3cret-password")
				.param("token", "wrong-token"))
			.andExpect(status().isOk());

		assertThat(accountRepository.findByEmail("rejected@example.com")).isEmpty();
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

	private void seedAccount(String email, String rawPassword, Role role, boolean active) {
		Account account = new Account();
		account.setEmail(email);
		account.setPasswordHash(passwordEncoder.encode(rawPassword));
		account.setRole(role);
		account.setActive(active);
		account.setCreatedAt(Instant.now());
		accountRepository.save(account);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresTestcontainersConfiguration {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}

	}

}

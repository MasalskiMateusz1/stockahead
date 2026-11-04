package pl.regavio.stockahead.account;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the last-active-manager rule under real row locks on real Postgres:
 * two managers deactivating each other at the same moment never leave their
 * company without an active manager, and one company's lock never delays
 * another company's account transition. Uses the latch-and-executor pattern
 * of {@code orders/CrossCompanyAllocationLockTests}.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class LastManagerConcurrencyTests {

	private static final String M1_EMAIL = "m1@last-manager-concurrency.example";

	private static final String M2_EMAIL = "m2@last-manager-concurrency.example";

	private static final String MANAGER_A_EMAIL = "manager-a@last-manager-concurrency.example";

	private static final String MANAGER_B_EMAIL = "manager-b@last-manager-concurrency.example";

	private static final String TECHNICIAN_A_EMAIL = "technician-a@last-manager-concurrency.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@last-manager-concurrency.example";

	private static final String PASSWORD = "correct-password";

	private static final int ITERATIONS = 8;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyRepository companyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private CompanyFixtures companyFixtures;

	@BeforeEach
	void setUp() {
		companyFixtures.cleanUp();
	}

	@AfterEach
	void tearDown() {
		companyFixtures.cleanUp();
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private boolean isActive(Account account) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT active FROM accounts WHERE id = ?",
				Boolean.class, account.getId()));
	}

	private int activeManagers(Company company) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM accounts WHERE company_id = ? AND role = 'MANAGER' AND active",
				Integer.class, company.getId());
		return count == null ? 0 : count;
	}

	/**
	 * A deactivation succeeded when it redirected back to the list without a
	 * flash error. A refusal carries the flash error; a caller already
	 * deactivated by the other thread is sent to {@code /login?deactivated}.
	 */
	private static boolean succeeded(MvcResult result) {
		return "/manager/accounts".equals(result.getResponse().getHeader("Location"))
				&& !result.getFlashMap().containsKey("error");
	}

	@Test
	void mutualDeactivationOfTwoManagersLeavesExactlyOneActive() throws Exception {
		Company company = companyFixtures.company("LastManagerConcurrencyTests Co");
		Account m1 = companyFixtures.account(company, M1_EMAIL, PASSWORD, Role.MANAGER, true);
		Account m2 = companyFixtures.account(company, M2_EMAIL, PASSWORD, Role.MANAGER, true);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			for (int iteration = 0; iteration < ITERATIONS; iteration++) {
				jdbcTemplate.update("UPDATE accounts SET active = true WHERE id IN (?, ?)", m1.getId(), m2.getId());
				MockHttpSession session1 = loginAs(M1_EMAIL);
				MockHttpSession session2 = loginAs(M2_EMAIL);
				CountDownLatch start = new CountDownLatch(1);

				Future<MvcResult> first = executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/manager/accounts/" + m2.getId() + "/deactivate")
							.session(session1).with(csrf()))
						.andExpect(status().is3xxRedirection())
						.andReturn();
				});
				Future<MvcResult> second = executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/manager/accounts/" + m1.getId() + "/deactivate")
							.session(session2).with(csrf()))
						.andExpect(status().is3xxRedirection())
						.andReturn();
				});
				start.countDown();
				List<MvcResult> results = new ArrayList<>();
				results.add(first.get(15, TimeUnit.SECONDS));
				results.add(second.get(15, TimeUnit.SECONDS));

				assertThat(results.stream().filter(LastManagerConcurrencyTests::succeeded).count())
					.as("iteration %d", iteration)
					.isEqualTo(1);
				assertThat(activeManagers(company)).as("iteration %d", iteration).isEqualTo(1);
				assertThat(isActive(m1) != isActive(m2)).as("iteration %d", iteration).isTrue();
			}
		}
		finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void companyAsHeldLockDoesNotDelayCompanyBsDeactivation() throws Exception {
		Company companyA = companyFixtures.company("LastManagerConcurrencyTests A");
		Company companyB = companyFixtures.company("LastManagerConcurrencyTests B");
		companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		Account technicianA = companyFixtures.account(companyA, TECHNICIAN_A_EMAIL, PASSWORD, Role.TECHNICIAN,
				true);
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		Account technicianB = companyFixtures.account(companyB, TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN,
				true);
		MockHttpSession sessionA = loginAs(MANAGER_A_EMAIL);
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		try {
			Future<Void> holder = executor.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					companyRepository.findByIdForUpdate(companyA.getId()).orElseThrow();
					locked.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				});
				return null;
			});
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			// Sanity check: A's own deactivation does wait on A's company lock.
			Future<MvcResult> deactivateA = executor.submit(() -> mockMvc
				.perform(post("/manager/accounts/" + technicianA.getId() + "/deactivate")
					.session(sessionA).with(csrf()))
				.andReturn());
			assertThatThrownBy(() -> deactivateA.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

			Future<MvcResult> deactivateB = executor.submit(() -> mockMvc
				.perform(post("/manager/accounts/" + technicianB.getId() + "/deactivate")
					.session(sessionB).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andReturn());
			assertThat(succeeded(deactivateB.get(15, TimeUnit.SECONDS))).isTrue();
			assertThat(isActive(technicianB)).isFalse();
			assertThat(isActive(technicianA)).isTrue();

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			assertThat(succeeded(deactivateA.get(10, TimeUnit.SECONDS))).isTrue();
		}
		finally {
			release.countDown();
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(isActive(technicianA)).isFalse();
	}

}

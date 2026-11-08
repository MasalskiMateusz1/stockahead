package pl.regavio.stockahead.account;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that {@link CompanyLifecycle}'s company row lock serialises
 * concurrent transitions on real Postgres: an approve and a reject of the same
 * PENDING company at the same moment leave exactly one outcome — ACTIVE with
 * every account live, or REJECTED with every account released — and the
 * loser is the stale no-op. Uses the latch-and-executor pattern of
 * {@link LastManagerConcurrencyTests}.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class CompanyLifecycleConcurrencyTests {

	private static final String OPERATOR_EMAIL = "operator@company-lifecycle-concurrency.example";

	private static final String PASSWORD = "correct-password";

	private static final int ITERATIONS = 8;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	@Test
	void simultaneousApproveAndRejectLeaveExactlyOneOutcome() throws Exception {
		companyFixtures.operator(OPERATOR_EMAIL, PASSWORD, true);
		MockHttpSession operator = (MockHttpSession) mockMvc
			.perform(formLogin().user(OPERATOR_EMAIL).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			for (int iteration = 0; iteration < ITERATIONS; iteration++) {
				Company company = companyFixtures.company("CompanyLifecycleConcurrencyTests Co " + iteration,
						CompanyStatus.PENDING);
				companyFixtures.account(company, "manager" + iteration + "@company-lifecycle-concurrency.example",
						PASSWORD, Role.MANAGER, true);
				companyFixtures.account(company, "technician" + iteration + "@company-lifecycle-concurrency.example",
						PASSWORD, Role.TECHNICIAN, true);
				CountDownLatch start = new CountDownLatch(1);

				Future<MvcResult> approve = executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/operator/companies/{id}/approve", company.getId())
							.session(operator).with(csrf()))
						.andExpect(status().is3xxRedirection())
						.andExpect(header().string("Location", "/operator/companies"))
						.andReturn();
				});
				Future<MvcResult> reject = executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/operator/companies/{id}/reject", company.getId())
							.session(operator).with(csrf()))
						.andExpect(status().is3xxRedirection())
						.andExpect(header().string("Location", "/operator/companies"))
						.andReturn();
				});
				start.countDown();
				List<MvcResult> results = List.of(approve.get(15, TimeUnit.SECONDS), reject.get(15, TimeUnit.SECONDS));

				assertThat(results.stream().filter(result -> result.getFlashMap().containsKey("success")).count())
					.as("iteration %d: exactly one transition applied", iteration)
					.isEqualTo(1);
				assertThat(results.stream().filter(result -> result.getFlashMap().containsKey("info")).count())
					.as("iteration %d: the other is the stale no-op", iteration)
					.isEqualTo(1);

				String status = jdbcTemplate.queryForObject("SELECT status FROM companies WHERE id = ?", String.class,
						company.getId());
				long live = count("SELECT count(*) FROM accounts WHERE company_id = ? AND active AND email_released_at IS NULL",
						company.getId());
				long released = count(
						"SELECT count(*) FROM accounts WHERE company_id = ? AND NOT active AND email_released_at IS NOT NULL",
						company.getId());
				assertThat(status).as("iteration %d", iteration).isIn("ACTIVE", "REJECTED");
				if (status.equals("ACTIVE")) {
					assertThat(results.get(0).getFlashMap().containsKey("success")).as("iteration %d", iteration).isTrue();
					assertThat(live).as("iteration %d", iteration).isEqualTo(2);
					assertThat(released).as("iteration %d", iteration).isZero();
				}
				else {
					assertThat(results.get(1).getFlashMap().containsKey("success")).as("iteration %d", iteration).isTrue();
					assertThat(live).as("iteration %d", iteration).isZero();
					assertThat(released).as("iteration %d", iteration).isEqualTo(2);
				}
			}
		}
		finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

}

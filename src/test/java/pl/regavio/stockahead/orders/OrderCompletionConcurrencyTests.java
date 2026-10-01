package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Callable;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves a completion report and a concurrent pick on the same order are
 * serialized: afterwards either the pick committed before the report, or it
 * was rejected — never a pick recorded after {@code completion_reported_at}.
 *
 * <p>
 * The final DB state alone can't tell "pick committed, then report" apart
 * from "report committed while the pick was still in flight, then the pick
 * committed anyway", so for the duration of each test a throwaway
 * {@code BEFORE UPDATE} trigger on {@code order_lines} widens the window
 * between a pick's state check and its commit ({@code pg_sleep}) and, at the
 * moment the pick's write lands, records the order in a log table if a
 * completion report is already committed. Without the report taking the
 * same part-row locks as a pick, the report commits during that sleep and
 * the log gets a row; with the locks, the report waits for the pick to
 * commit and the log stays empty. Same {@code MockMvc}/shared-session style
 * as {@code PickingConcurrencyTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompletionConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "order-completion-concurrency-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String COMPLETION_REPORTED_ERROR =
			"Zlecenie zostało zgłoszone jako zakończone — pobieranie jest wstrzymane.";

	private static final int ITERATIONS = 6;

	private static final int INITIAL_STOCK = 10;

	private static final int PICK_QUANTITY = 2;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private Long projectId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		installPickAfterReportDetector();
		projectId = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES ('Completion Concurrency Board', true) RETURNING id",
				Long.class));
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropPickAfterReportDetector();
		jdbcTemplate.update("DELETE FROM order_lines");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
	}

	private void installPickAfterReportDetector() {
		jdbcTemplate.execute("CREATE TABLE test_pick_after_report (order_id BIGINT NOT NULL)");
		jdbcTemplate.execute("""
				CREATE FUNCTION test_pick_after_report_check() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.2);
					IF EXISTS (SELECT 1 FROM orders WHERE id = NEW.order_id
							AND completion_reported_at IS NOT NULL) THEN
						INSERT INTO test_pick_after_report (order_id) VALUES (NEW.order_id);
					END IF;
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_pick_after_report_trigger BEFORE UPDATE ON order_lines "
				+ "FOR EACH ROW WHEN (NEW.picked_quantity > OLD.picked_quantity) "
				+ "EXECUTE FUNCTION test_pick_after_report_check()");
	}

	private void dropPickAfterReportDetector() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_pick_after_report_trigger ON order_lines");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_pick_after_report_check()");
		jdbcTemplate.execute("DROP TABLE IF EXISTS test_pick_after_report");
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession technicianSession() throws Exception {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(TECHNICIAN_EMAIL);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(Role.TECHNICIAN);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
		return (MockHttpSession) mockMvc.perform(formLogin().user(TECHNICIAN_EMAIL).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedPart(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				INITIAL_STOCK));
	}

	/** An OPEN order that is already taken, so it can be reported right away. */
	private Long seedTakenOrder() {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at, taken_at) "
						+ "VALUES (?, 1, 'NORMAL', ?, ?, ?) RETURNING id",
				Long.class, projectId, LocalDate.now().plusDays(7), Timestamp.from(Instant.now()),
				Timestamp.from(Instant.now())));
	}

	private Long seedOrderLine(Long orderId, Long partId) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, 5, 5) RETURNING id",
				Long.class, orderId, partId));
	}

	private int pickedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	// ---- tests ----------------------------------------------------------

	@Test
	void concurrentPickAndReportNeverRecordAPickAfterTheReport() throws Exception {
		MockHttpSession session = technicianSession();

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Race Part " + iteration);
			Long orderId = seedTakenOrder();
			Long lineId = seedOrderLine(orderId, partId);
			// Alternate between both requests starting together and the report
			// arriving while the pick is already inside its (slowed) write.
			long reportDelayMillis = iteration % 2 == 0 ? 0 : 50;

			CountDownLatch bothReady = new CountDownLatch(2);
			ExecutorService executor = Executors.newFixedThreadPool(2);

			Callable<MvcResult> pickTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId)
					.session(session)
					.with(csrf())
					.param("quantity", Integer.toString(PICK_QUANTITY))).andReturn();
			};
			Callable<MvcResult> reportTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				Thread.sleep(reportDelayMillis);
				return mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session)
					.with(csrf())).andReturn();
			};

			MvcResult pickResult;
			MvcResult reportResult;
			try {
				Future<MvcResult> pickFuture = executor.submit(pickTask);
				Future<MvcResult> reportFuture = executor.submit(reportTask);
				pickResult = pickFuture.get(15, TimeUnit.SECONDS);
				reportResult = reportFuture.get(15, TimeUnit.SECONDS);
			}
			finally {
				executor.shutdownNow();
			}

			// The report always wins eventually: either it ran first, or it waited
			// for the pick to commit.
			assertThat(reportResult.getResponse().getStatus()).isEqualTo(302);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT completion_reported_at FROM orders WHERE id = ?", Timestamp.class, orderId))
				.isNotNull();

			// Never a pick written once the report was already committed.
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM test_pick_after_report WHERE order_id = ?", Integer.class, orderId))
				.as("iteration %d: pick written after the completion report committed", iteration)
				.isZero();

			int picked = pickedQuantityOf(lineId);
			assertThat(stockOf(partId)).isEqualTo(INITIAL_STOCK - picked);
			if (picked == 0) {
				// The pick lost the race and must have been rejected, not silently dropped.
				assertThat(pickResult.getResponse().getStatus()).isEqualTo(200);
				assertThat(pickResult.getResponse().getContentAsString()).contains(COMPLETION_REPORTED_ERROR);
			}
			else {
				assertThat(picked).isEqualTo(PICK_QUANTITY);
				assertThat(pickResult.getResponse().getStatus()).isEqualTo(302);
			}
		}
	}

}

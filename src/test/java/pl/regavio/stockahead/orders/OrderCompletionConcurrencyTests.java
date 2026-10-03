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
 * as {@code PickingConcurrencyTests}. Also races confirm against order
 * creation and against a pick on a short taken order the confirm tops up.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompletionConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "order-completion-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "order-completion-concurrency-manager@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String COMPLETION_REPORTED_ERROR =
			"Zlecenie zostało zgłoszone jako zakończone — pobieranie jest wstrzymane.";

	private static final int ITERATIONS = 6;

	private static final int INITIAL_STOCK = 10;

	/** The real first pick that makes the order taken before the race starts. */
	private static final int FIRST_PICK_QUANTITY = 1;

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
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
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
		return sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
	}

	private MockHttpSession sessionFor(String email, Role role) throws Exception {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedPart(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				INITIAL_STOCK));
	}

	private Long seedPartWithStock(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	/** A not-yet-taken OPEN order holding the given reservation, as the allocator would leave it. */
	private Long seedUntakenOrderLine(Long partId, int required, int reserved) {
		return transactionTemplate.execute(status -> {
			Long orderId = jdbcTemplate.queryForObject(
					"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
							+ "VALUES (?, 1, 'HIGH', ?, ?) RETURNING id",
					Long.class, projectId, LocalDate.now().plusDays(3), Timestamp.from(Instant.now()));
			jdbcTemplate.update("INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
					+ "VALUES (?, ?, ?, ?)", orderId, partId, required, reserved);
			return orderId;
		});
	}

	/** An OPEN order with an explicit priority and created-at, holding the given reservation. */
	private Long seedOrderLine(Long partId, Priority priority, Instant createdAt, int required, int reserved) {
		return transactionTemplate.execute(status -> {
			Long orderId = jdbcTemplate.queryForObject(
					"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
							+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
					Long.class, projectId, priority.name(), LocalDate.now().plusDays(3), Timestamp.from(createdAt));
			jdbcTemplate.update("INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
					+ "VALUES (?, ?, ?, ?)", orderId, partId, required, reserved);
			return orderId;
		});
	}

	private Long lineIdOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, orderId);
	}

	private int pickedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private int reservedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	// ---- tests ----------------------------------------------------------

	@Test
	void concurrentPickAndReportNeverRecordAPickAfterTheReport() throws Exception {
		MockHttpSession session = technicianSession();

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Race Part " + iteration);
			Long orderId = seedUntakenOrderLine(partId, 5, 5);
			Long lineId = lineIdOf(orderId);
			// Take the order through the real first pick, so it can be reported.
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
				.with(csrf())
				.param("quantity", Integer.toString(FIRST_PICK_QUANTITY)))
				.andExpect(status().is3xxRedirection());
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
					.with(csrf()).param("builtUnits", "1")).andReturn();
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
			assertThat(jdbcTemplate.queryForObject("SELECT built_units FROM orders WHERE id = ?", Integer.class,
					orderId))
				.isEqualTo(1);

			// Never a pick written once the report was already committed.
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM test_pick_after_report WHERE order_id = ?", Integer.class, orderId))
				.as("iteration %d: pick written after the completion report committed", iteration)
				.isZero();

			int picked = pickedQuantityOf(lineId);
			assertThat(stockOf(partId)).isEqualTo(INITIAL_STOCK - picked);
			if (picked == FIRST_PICK_QUANTITY) {
				// The pick lost the race and must have been rejected, not silently dropped.
				assertThat(pickResult.getResponse().getStatus()).isEqualTo(200);
				assertThat(pickResult.getResponse().getContentAsString()).contains(COMPLETION_REPORTED_ERROR);
			}
			else {
				assertThat(picked).isEqualTo(FIRST_PICK_QUANTITY + PICK_QUANTITY);
				assertThat(pickResult.getResponse().getStatus()).isEqualTo(302);
			}
		}
	}

	/**
	 * Confirm is the first transition that frees reserved stock. Racing it
	 * against a manager creating a competing order on the same part must
	 * never over-reserve or fail: whichever commits first, the new order ends
	 * up holding every unit the confirmed order released. The confirmed order
	 * reaches "taken" and "reported" through the real pick and report
	 * endpoints, never a raw-SQL shortcut.
	 */
	@Test
	void concurrentConfirmAndOrderCreationHandReleasedUnitsToTheNewOrderWithoutOverReserving() throws Exception {
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			int iterationIndex = iteration;
			Long partId = seedPartWithStock("Confirm Race Part " + iteration, 6);
			Long newProjectId = transactionTemplate.execute(status -> {
				Long id = jdbcTemplate.queryForObject(
						"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class,
						"Confirm Race Board " + iterationIndex);
				jdbcTemplate.update("INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, 4)",
						id, partId);
				return id;
			});
			Long confirmedOrderId = seedUntakenOrderLine(partId, 6, 6);
			Long confirmedLineId = lineIdOf(confirmedOrderId);

			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", confirmedOrderId, confirmedLineId)
				.session(technician).with(csrf()).param("quantity", "2"))
				.andExpect(status().is3xxRedirection());
			mockMvc.perform(post("/picking/{orderId}/report-completion", confirmedOrderId)
				.session(technician).with(csrf()).param("builtUnits", "1"))
				.andExpect(status().is3xxRedirection());
			assertThat(stockOf(partId)).isEqualTo(4);

			// Alternate which request goes first, so both orderings are exercised:
			// creation-then-confirm needs the confirm's reallocation to hand the
			// released units over; confirm-then-creation needs the creation's own
			// allocation to see them.
			long confirmDelayMillis = iteration % 2 == 0 ? 50 : 0;
			long createDelayMillis = iteration % 2 == 0 ? 0 : 50;
			CountDownLatch bothReady = new CountDownLatch(2);
			ExecutorService executor = Executors.newFixedThreadPool(2);

			Callable<MvcResult> confirmTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				Thread.sleep(confirmDelayMillis);
				return mockMvc.perform(post("/orders/{id}/confirm-completion", confirmedOrderId).session(manager)
					.with(csrf())).andReturn();
			};
			Callable<MvcResult> createTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				Thread.sleep(createDelayMillis);
				return mockMvc.perform(post("/orders").session(manager)
					.with(csrf())
					.param("projectId", newProjectId.toString())
					.param("quantityUnits", "1")
					.param("priority", "LOW")
					.param("requiredDate", LocalDate.now().plusDays(30).toString())).andReturn();
			};

			MvcResult confirmResult;
			MvcResult createResult;
			try {
				Future<MvcResult> confirmFuture = executor.submit(confirmTask);
				Future<MvcResult> createFuture = executor.submit(createTask);
				confirmResult = confirmFuture.get(15, TimeUnit.SECONDS);
				createResult = createFuture.get(15, TimeUnit.SECONDS);
			}
			finally {
				executor.shutdownNow();
			}

			assertThat(confirmResult.getResponse().getStatus()).as("iteration %d: confirm", iteration).isEqualTo(302);
			assertThat(createResult.getResponse().getStatus()).as("iteration %d: create", iteration).isEqualTo(302);

			assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class,
					confirmedOrderId)).isEqualTo("COMPLETED");
			assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?",
					Integer.class, confirmedLineId)).isZero();
			assertThat(pickedQuantityOf(confirmedLineId)).isEqualTo(2);
			assertThat(stockOf(partId)).isEqualTo(4);

			Integer reservedOnOpenOrders = jdbcTemplate.queryForObject(
					"SELECT COALESCE(SUM(ol.reserved_quantity), 0) FROM order_lines ol JOIN orders o ON o.id = ol.order_id "
							+ "WHERE ol.part_id = ? AND o.status = 'OPEN'",
					Integer.class, partId);
			assertThat(reservedOnOpenOrders).as("iteration %d: reserved vs stock", iteration).isLessThanOrEqualTo(4);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT ol.reserved_quantity FROM order_lines ol JOIN orders o ON o.id = ol.order_id "
							+ "WHERE o.project_id = ?",
					Integer.class, newProjectId))
				.as("iteration %d: new order receives the released units", iteration)
				.isEqualTo(4);
		}
	}

	/**
	 * Confirm tops up short taken orders, so it writes a taken line a
	 * technician may be picking at the same moment. Racing confirm of A
	 * against a pick on short taken order B (same part) must serialize on the
	 * part-row lock. The pick asks for more than B held before the top-up, so
	 * the two legal orderings end in two distinct exact states, and no
	 * invariant is ever broken in either. A and B
	 * reach "taken" (and A "reported") only through the real endpoints.
	 */
	@Test
	void concurrentConfirmAndPickOnTheTakenOrderBeingToppedUp() throws Exception {
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPartWithStock("Top-Up Race Part " + iteration, 10);
			Instant now = Instant.now();
			Long confirmedOrderId = seedOrderLine(partId, Priority.HIGH, now.minusSeconds(20), 6, 6);
			Long confirmedLineId = lineIdOf(confirmedOrderId);
			Long shortOrderId = seedOrderLine(partId, Priority.NORMAL, now.minusSeconds(10), 8, 4);
			Long shortLineId = lineIdOf(shortOrderId);

			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", confirmedOrderId, confirmedLineId)
				.session(technician).with(csrf()).param("quantity", "1"))
				.andExpect(status().is3xxRedirection());
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", shortOrderId, shortLineId)
				.session(technician).with(csrf()).param("quantity", "1"))
				.andExpect(status().is3xxRedirection());
			mockMvc.perform(post("/picking/{orderId}/report-completion", confirmedOrderId)
				.session(technician).with(csrf()).param("builtUnits", "1"))
				.andExpect(status().is3xxRedirection());
			assertThat(stockOf(partId)).isEqualTo(8);
			assertThat(reservedQuantityOf(shortLineId)).isEqualTo(3);

			// Alternate which request goes first, as in the confirm-vs-create race.
			long confirmDelayMillis = iteration % 2 == 0 ? 50 : 0;
			long pickDelayMillis = iteration % 2 == 0 ? 0 : 50;
			CountDownLatch bothReady = new CountDownLatch(2);
			ExecutorService executor = Executors.newFixedThreadPool(2);

			Callable<MvcResult> confirmTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				Thread.sleep(confirmDelayMillis);
				return mockMvc.perform(post("/orders/{id}/confirm-completion", confirmedOrderId).session(manager)
					.with(csrf())).andReturn();
			};
			Callable<MvcResult> pickTask = () -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				Thread.sleep(pickDelayMillis);
				return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", shortOrderId, shortLineId)
					.session(technician)
					.with(csrf())
					.param("quantity", "4")).andReturn();
			};

			MvcResult confirmResult;
			MvcResult pickResult;
			try {
				Future<MvcResult> confirmFuture = executor.submit(confirmTask);
				Future<MvcResult> pickFuture = executor.submit(pickTask);
				confirmResult = confirmFuture.get(15, TimeUnit.SECONDS);
				pickResult = pickFuture.get(15, TimeUnit.SECONDS);
			}
			finally {
				executor.shutdownNow();
			}

			assertThat(confirmResult.getResponse().getStatus()).as("iteration %d: confirm", iteration).isEqualTo(302);
			// The pick asks for 4, more than B's 3 reserved before the top-up: it
			// succeeds (302) only if confirm committed first, else it is rejected
			// with the form re-rendered (200).
			int pickStatus = pickResult.getResponse().getStatus();
			assertThat(pickStatus).as("iteration %d: pick", iteration).isIn(200, 302);
			assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class,
					confirmedOrderId)).isEqualTo("COMPLETED");

			int stock = stockOf(partId);
			assertThat(stock).as("iteration %d: stock", iteration).isNotNegative();
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM order_lines WHERE part_id = ? "
							+ "AND reserved_quantity + picked_quantity > required_quantity",
					Integer.class, partId))
				.as("iteration %d: reserved + picked over required", iteration)
				.isZero();
			Integer reservedOnOpenOrders = jdbcTemplate.queryForObject(
					"SELECT COALESCE(SUM(ol.reserved_quantity), 0) FROM order_lines ol JOIN orders o ON o.id = ol.order_id "
							+ "WHERE ol.part_id = ? AND o.status = 'OPEN'",
					Integer.class, partId);
			assertThat(reservedOnOpenOrders).as("iteration %d: reserved vs stock", iteration)
				.isLessThanOrEqualTo(stock);

			if (pickStatus == 302) {
				// Confirm first: B topped up 3 -> 7, then the pick takes 4 of the top-up.
				assertThat(stock).as("iteration %d: stock after top-up then pick", iteration).isEqualTo(4);
				assertThat(pickedQuantityOf(shortLineId)).isEqualTo(5);
				assertThat(reservedQuantityOf(shortLineId)).isEqualTo(3);
			}
			else {
				// Pick first: rejected at 3 reserved; confirm then tops B up to 7.
				assertThat(stock).as("iteration %d: stock after rejected pick", iteration).isEqualTo(8);
				assertThat(pickedQuantityOf(shortLineId)).isEqualTo(1);
				assertThat(reservedQuantityOf(shortLineId)).isEqualTo(7);
			}
		}
	}

}

package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@code OrderController.cancel} and {@code OrderController.change}
 * are serialized against a concurrent pick on the same order: a cancel and a
 * pick never interleave so that a pick lands on a {@code CANCELLED} order or
 * a cancel silently turns a just-committed pick into "used"; a schedule
 * change never applies to an order a pick took in the meantime; and a stale
 * cancel form (seen-picked lower than current) is rejected without touching
 * a single row.
 *
 * <p>
 * As in {@code OrderCompletionConcurrencyTests}, final DB state alone can't
 * always tell a safe ordering from an unsafe interleaving, so throwaway
 * triggers are installed for each test: they widen the windows between a
 * request's state check and its commit ({@code pg_sleep} on a pick's line
 * write, on the order's first take, and on a cancel's status write) and, at
 * the moment a write lands, log a violation if the already-committed state
 * says it must not happen — a pick on a {@code CANCELLED} order, or a
 * priority/date change on an already taken order (or a change that clears
 * {@code taken_at} with a stale full-row update). With the shared part-row
 * locks every logged condition is impossible and the log stays empty. Taken
 * states come from the real pick endpoint; only the starting order and its
 * reservation are seeded with raw SQL, as in the sibling concurrency tests.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "order-cancel-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "order-cancel-concurrency-manager@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String ORDER_NOT_OPEN_ERROR = "Zlecenie nie jest już otwarte.";

	private static final String PICKED_CHANGED_ERROR = "Stan pobrań zlecenia zmienił się — sprawdź ilości do zwrotu.";

	private static final String NOT_CHANGEABLE_ERROR = "Nie można zmienić zlecenia, które zostało już podjęte lub nie jest otwarte.";

	private static final int ITERATIONS = 6;

	private static final int INITIAL_STOCK = 5;

	private static final int REQUIRED = 5;

	/** The real first pick that makes the order taken before the cancel race starts. */
	private static final int FIRST_PICK_QUANTITY = 1;

	private static final int RACE_PICK_QUANTITY = 2;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company company;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private Long projectId;

	private Long otherProjectId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		installDetectors();
		projectId = seedProject("Cancel Concurrency Board");
		otherProjectId = seedProject("Cancel Concurrency Waiting Board");
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropDetectors();
		jdbcTemplate.update("DELETE FROM order_lines");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	/**
	 * Installs the violation log and two triggers. On {@code order_lines}, a
	 * pick's write (picked quantity rising) sleeps, then logs
	 * {@code PICK_ON_CANCELLED} if the order is already committed as
	 * {@code CANCELLED}. On {@code orders}, a cancel's status write and a
	 * pick's first take sleep (widening each request's check-to-commit
	 * window), and a priority/date change landing on a row that is already
	 * taken logs {@code CHANGE_ON_TAKEN}, while a write that clears
	 * {@code taken_at} logs {@code TAKEN_CLEARED}. Under READ COMMITTED a
	 * blocked {@code UPDATE} re-reads the latest committed row, so
	 * {@code OLD} there is the state the write actually lands on.
	 */
	private void installDetectors() {
		jdbcTemplate.execute("CREATE TABLE test_order_race_violations (order_id BIGINT NOT NULL, kind TEXT NOT NULL)");
		jdbcTemplate.execute("""
				CREATE FUNCTION test_pick_on_cancelled_check() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.2);
					IF EXISTS (SELECT 1 FROM orders WHERE id = NEW.order_id AND status = 'CANCELLED') THEN
						INSERT INTO test_order_race_violations (order_id, kind) VALUES (NEW.order_id, 'PICK_ON_CANCELLED');
					END IF;
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_pick_on_cancelled_trigger BEFORE UPDATE ON order_lines "
				+ "FOR EACH ROW WHEN (NEW.picked_quantity > OLD.picked_quantity) "
				+ "EXECUTE FUNCTION test_pick_on_cancelled_check()");
		jdbcTemplate.execute("""
				CREATE FUNCTION test_order_write_check() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF OLD.status = 'OPEN' AND NEW.status = 'CANCELLED' THEN
						PERFORM pg_sleep(0.2);
					END IF;
					IF OLD.taken_at IS NULL AND NEW.taken_at IS NOT NULL THEN
						PERFORM pg_sleep(0.2);
					END IF;
					IF OLD.taken_at IS NOT NULL AND (NEW.priority IS DISTINCT FROM OLD.priority
							OR NEW.required_date IS DISTINCT FROM OLD.required_date) THEN
						INSERT INTO test_order_race_violations (order_id, kind) VALUES (NEW.id, 'CHANGE_ON_TAKEN');
					END IF;
					IF OLD.taken_at IS NOT NULL AND NEW.taken_at IS NULL THEN
						INSERT INTO test_order_race_violations (order_id, kind) VALUES (NEW.id, 'TAKEN_CLEARED');
					END IF;
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_order_write_trigger BEFORE UPDATE ON orders "
				+ "FOR EACH ROW EXECUTE FUNCTION test_order_write_check()");
	}

	private void dropDetectors() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_pick_on_cancelled_trigger ON order_lines");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_order_write_trigger ON orders");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_pick_on_cancelled_check()");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_order_write_check()");
		jdbcTemplate.execute("DROP TABLE IF EXISTS test_order_race_violations");
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("OrderCancelConcurrencyTests Co");
		}
		return company;
	}

	private MockHttpSession sessionFor(String email, Role role) throws Exception {
		companyFixtures.account(company(), email, PASSWORD, role, true);
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedProject(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, name));
	}

	private Long seedPart(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	/** An untaken OPEN order with an explicit priority, date and created-at. */
	private Long seedOrder(Long orderProjectId, Priority priority, LocalDate requiredDate, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, orderProjectId, priority.name(), requiredDate, Timestamp.from(createdAt)));
	}

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity));
	}

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));
	}

	private int violationsFor(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_order_race_violations WHERE order_id = ?",
				Integer.class, orderId);
	}

	private String statusOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private Timestamp cancelledAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT cancelled_at FROM orders WHERE id = ?", Timestamp.class,
				orderId);
	}

	private Timestamp takenAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT taken_at FROM orders WHERE id = ?", Timestamp.class, orderId);
	}

	private String priorityOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT priority FROM orders WHERE id = ?", String.class, orderId);
	}

	private LocalDate requiredDateOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT required_date FROM orders WHERE id = ?", LocalDate.class,
				orderId);
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int returnedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT returned_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	/** Every row of the tables a cancel can write, in a stable order. */
	private List<List<Map<String, Object>>> snapshotRows() {
		return List.of(jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id"),
				jdbcTemplate.queryForList("SELECT * FROM order_lines ORDER BY id"),
				jdbcTemplate.queryForList("SELECT * FROM parts ORDER BY id"));
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

	// ---- tests ----------------------------------------------------------

	/**
	 * The manager submits the cancel form for a taken order (it showed the
	 * line with the first pick's amount, all of it returned) while a
	 * technician picks more from the same line. Either the pick commits first
	 * and the cancel, comparing against the form's stale seen-picked value, is
	 * rejected; or the cancel commits first and the pick is refused as
	 * {@code orderNotOpen}. Never a pick on a {@code CANCELLED} order, never a
	 * cancel that swallows a pick it did not show, and stock always equals
	 * the initial stock minus kept picks plus returns.
	 */
	@Test
	void concurrentPickAndCancelNeverRecordAPickOnACancelledOrder() throws Exception {
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Cancel Race Part " + iteration, INITIAL_STOCK);
			Long orderId = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long lineId = seedOrderLine(orderId, partId, REQUIRED, REQUIRED);
			pick(technician, orderId, lineId, FIRST_PICK_QUANTITY);
			// Alternate between both requests starting together and the cancel
			// arriving while the pick is already inside its (slowed) write, and
			// the pick arriving while the cancel is inside its (slowed) write.
			long cancelDelayMillis = iteration % 2 == 1 ? 50 : 0;
			long pickDelayMillis = iteration % 3 == 2 ? 50 : 0;

			MvcResult[] results = race(() -> {
				Thread.sleep(pickDelayMillis);
				return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId)
					.session(technician)
					.with(csrf())
					.param("quantity", Integer.toString(RACE_PICK_QUANTITY))).andReturn();
			}, () -> {
				Thread.sleep(cancelDelayMillis);
				return mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
					.with(csrf())
					.param("seenPicked_" + lineId, Integer.toString(FIRST_PICK_QUANTITY))
					.param("returned_" + lineId, Integer.toString(FIRST_PICK_QUANTITY))).andReturn();
			});
			MvcResult pickResult = results[0];
			MvcResult cancelResult = results[1];

			assertThat(violationsFor(orderId)).as("iteration %d: pick written on a cancelled order", iteration)
				.isZero();

			int picked = pickedOf(lineId);
			int returned = returnedOf(lineId);
			int stock = stockOf(partId);
			assertThat(stock).as("iteration %d: stock", iteration).isNotNegative();
			assertThat(stock).as("iteration %d: stock = initial - kept picks + returns", iteration)
				.isEqualTo(INITIAL_STOCK - picked + returned);

			if ("CANCELLED".equals(statusOf(orderId))) {
				// Cancel first: it returned exactly what it showed, and the pick was refused.
				assertThat(cancelResult.getResponse().getStatus()).as("iteration %d: cancel", iteration)
					.isEqualTo(302);
				assertThat(pickResult.getResponse().getStatus()).as("iteration %d: pick", iteration).isEqualTo(200);
				assertThat(pickResult.getResponse().getContentAsString()).contains(ORDER_NOT_OPEN_ERROR);
				assertThat(picked).as("iteration %d: picked", iteration).isEqualTo(FIRST_PICK_QUANTITY);
				assertThat(returned).isEqualTo(FIRST_PICK_QUANTITY);
				assertThat(reservedOf(lineId)).isZero();
				assertThat(stock).isEqualTo(INITIAL_STOCK);
			}
			else {
				// Pick first: the cancel's form is stale and is rejected with no change.
				assertThat(statusOf(orderId)).isEqualTo("OPEN");
				assertThat(pickResult.getResponse().getStatus()).as("iteration %d: pick", iteration).isEqualTo(302);
				assertThat(cancelResult.getResponse().getStatus()).as("iteration %d: cancel", iteration)
					.isEqualTo(200);
				assertThat(cancelResult.getResponse().getContentAsString()).contains(PICKED_CHANGED_ERROR);
				assertThat(cancelledAtOf(orderId)).isNull();
				assertThat(picked).as("iteration %d: picked", iteration)
					.isEqualTo(FIRST_PICK_QUANTITY + RACE_PICK_QUANTITY);
				assertThat(returned).isZero();
				assertThat(reservedOf(lineId)).isEqualTo(REQUIRED - FIRST_PICK_QUANTITY - RACE_PICK_QUANTITY);
				assertThat(stock).isEqualTo(INITIAL_STOCK - FIRST_PICK_QUANTITY - RACE_PICK_QUANTITY);
			}
		}
	}

	/**
	 * The manager changes the priority and date of an untaken order while a
	 * technician makes its first pick. Either the change commits first (and
	 * the pick follows, taking the order with its new schedule), or the pick
	 * takes the order first and the change is refused as
	 * {@code notChangeable}. Never a change applied to an order that is
	 * already taken, and never a taken order put back to untaken.
	 */
	@Test
	void concurrentFirstPickAndChangeNeverChangeATakenOrder() throws Exception {
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);
		LocalDate originalDate = LocalDate.now().plusDays(7);
		LocalDate changedDate = LocalDate.now().plusDays(2);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Change Race Part " + iteration, INITIAL_STOCK);
			Long orderId = seedOrder(projectId, Priority.NORMAL, originalDate, Instant.now());
			Long lineId = seedOrderLine(orderId, partId, REQUIRED, REQUIRED);
			// Alternate between both starting together, the change arriving while
			// the pick is inside its (slowed) take, and the pick going second.
			long changeDelayMillis = iteration % 3 == 1 ? 50 : 0;
			long pickDelayMillis = iteration % 3 == 2 ? 50 : 0;

			MvcResult[] results = race(() -> {
				Thread.sleep(pickDelayMillis);
				return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId)
					.session(technician)
					.with(csrf())
					.param("quantity", Integer.toString(RACE_PICK_QUANTITY))).andReturn();
			}, () -> {
				Thread.sleep(changeDelayMillis);
				return mockMvc.perform(post("/orders/{id}/change", orderId).session(manager)
					.with(csrf())
					.param("priority", Priority.HIGH.name())
					.param("requiredDate", changedDate.toString())).andReturn();
			});
			MvcResult pickResult = results[0];
			MvcResult changeResult = results[1];

			assertThat(violationsFor(orderId)).as("iteration %d: change written on a taken order", iteration)
				.isZero();

			// The pick always succeeds: the order stays OPEN with its reservation either way.
			assertThat(pickResult.getResponse().getStatus()).as("iteration %d: pick", iteration).isEqualTo(302);
			assertThat(takenAtOf(orderId)).as("iteration %d: taken", iteration).isNotNull();
			assertThat(pickedOf(lineId)).isEqualTo(RACE_PICK_QUANTITY);
			assertThat(reservedOf(lineId)).isEqualTo(REQUIRED - RACE_PICK_QUANTITY);
			assertThat(stockOf(partId)).isEqualTo(INITIAL_STOCK - RACE_PICK_QUANTITY);

			int changeStatus = changeResult.getResponse().getStatus();
			assertThat(changeStatus).as("iteration %d: change", iteration).isIn(200, 302);
			if (changeStatus == 302) {
				// Change first, while still untaken; the pick then took the order.
				assertThat(priorityOf(orderId)).isEqualTo("HIGH");
				assertThat(requiredDateOf(orderId)).isEqualTo(changedDate);
			}
			else {
				// Pick first: the change saw a taken order and was refused.
				assertThat(changeResult.getResponse().getContentAsString()).contains(NOT_CHANGEABLE_ERROR);
				assertThat(priorityOf(orderId)).isEqualTo("NORMAL");
				assertThat(requiredDateOf(orderId)).isEqualTo(originalDate);
			}
		}
	}

	/**
	 * A cancel form that loaded before further picks is stale: submitted
	 * later, it is rejected and no row changes — whether the shown line's
	 * seen-picked is lower than its current picked amount, or a line the form
	 * did not show (seen as 0) was picked since. Lines reach their picked
	 * amounts through the real pick endpoint, the shown line picked twice.
	 * A waiting order on the same part means an applied cancel would also
	 * have reallocated; resubmitting with the current amounts then succeeds.
	 */
	@Test
	void staleCancelFormIsRejectedAndChangesNoRow() throws Exception {
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);
		Instant now = Instant.now();
		Long shownPart = seedPart("Stale Form Shown Part", 10);
		Long hiddenPart = seedPart("Stale Form Hidden Part", 10);
		Long orderId = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(7), now.minusSeconds(60));
		Long shownLine = seedOrderLine(orderId, shownPart, 6, 6);
		Long hiddenLine = seedOrderLine(orderId, hiddenPart, 6, 6);
		Long waitingOrder = seedOrder(otherProjectId, Priority.NORMAL, LocalDate.now().plusDays(7), now);
		Long waitingLine = seedOrderLine(waitingOrder, shownPart, 10, 4);

		// The form loaded after this pick: it shows the line with 2 picked.
		pick(technician, orderId, shownLine, 2);
		// Picks committed after the form loaded.
		pick(technician, orderId, shownLine, 3);
		pick(technician, orderId, hiddenLine, 1);

		List<List<Map<String, Object>>> before = snapshotRows();

		// Shown line seen lower than current (2 < 5); the hidden line's value is current.
		mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
			.with(csrf())
			.param("seenPicked_" + shownLine, "2")
			.param("returned_" + shownLine, "2")
			.param("seenPicked_" + hiddenLine, "1")
			.param("returned_" + hiddenLine, "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PICKED_CHANGED_ERROR)));
		assertThat(snapshotRows()).isEqualTo(before);

		// Shown line current, but the hidden line (not on the form, so seen as 0) was picked since.
		mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
			.with(csrf())
			.param("seenPicked_" + shownLine, "5")
			.param("returned_" + shownLine, "5"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PICKED_CHANGED_ERROR)));
		assertThat(snapshotRows()).isEqualTo(before);

		assertThat(statusOf(orderId)).isEqualTo("OPEN");
		assertThat(cancelledAtOf(orderId)).isNull();
		assertThat(stockOf(shownPart)).isEqualTo(5);
		assertThat(stockOf(hiddenPart)).isEqualTo(9);
		assertThat(returnedOf(shownLine)).isZero();
		assertThat(reservedOf(waitingLine)).isEqualTo(4);

		// A form matching the current picks goes through.
		mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
			.with(csrf())
			.param("seenPicked_" + shownLine, "5")
			.param("returned_" + shownLine, "5")
			.param("seenPicked_" + hiddenLine, "1")
			.param("returned_" + hiddenLine, "0"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderId));
		assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
		assertThat(stockOf(shownPart)).isEqualTo(10);
		assertThat(stockOf(hiddenPart)).isEqualTo(9);
		assertThat(reservedOf(waitingLine)).isEqualTo(10);
		assertThat(violationsFor(orderId)).isZero();
	}

}

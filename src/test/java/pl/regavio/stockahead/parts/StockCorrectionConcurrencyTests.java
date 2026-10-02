package pl.regavio.stockahead.parts;

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
import java.util.function.IntUnaryOperator;

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
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@code StockCorrectionController}'s set-total and adjust writes
 * can't lose an update and can't break the stock/reservation invariants
 * (AGENTS.md: stock never below zero, no unit reserved twice) when they race
 * a delivery, a pick, or the creation of a new order on the same part,
 * against real Postgres via Testcontainers. Mirrors
 * {@code DeliveryConcurrencyTests}: raw SQL fixtures, the real HTTP routes
 * through {@code MockMvc} with logged-in {@link MockHttpSession}s, two
 * threads released together by a {@link CountDownLatch}, and throwaway
 * {@code pg_sleep} triggers that widen the windows.
 *
 * <p>
 * The trigger on every stock-changing {@code UPDATE} of {@code parts} holds
 * each request inside its read-to-commit window, so without the part-row lock
 * both transactions would read the old stock and the second write would
 * overwrite the first. A second trigger slows the {@code INSERT} of a new
 * order the same way. Each race is repeated with alternating start offsets.
 * Where the final state does not depend on which request wins the lock it is
 * asserted exactly; where it does (a request rejected against the stock the
 * other one left), each test accepts exactly the two serial outcomes and
 * tells them apart by which request succeeded. Every iteration also checks
 * the invariants as SQL over {@code parts}/{@code order_lines} and that
 * exactly one {@code stock_corrections} row exists per successful
 * correction.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockCorrectionConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "correction-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "correction-concurrency-manager@example.com";

	private static final String PASSWORD = "correct-password";

	private static final int ITERATIONS = 4;

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
		installSlowdowns();
		projectId = seedProject("Correction Concurrency Board");
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropSlowdowns();
		jdbcTemplate.update("DELETE FROM stock_corrections");
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

	/**
	 * A stock change on {@code parts} (a correction, a receipt's increment or
	 * a pick's decrement) and a new {@code orders} row each sleep before
	 * landing, so the writing transaction stays open well past the moment a
	 * competing request reads the same part.
	 */
	private void installSlowdowns() {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_correction_slow_write() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.2);
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_correction_slow_stock_trigger BEFORE UPDATE ON parts "
				+ "FOR EACH ROW WHEN (NEW.quantity IS DISTINCT FROM OLD.quantity) "
				+ "EXECUTE FUNCTION test_correction_slow_write()");
		jdbcTemplate.execute("CREATE TRIGGER test_correction_slow_order_trigger BEFORE INSERT ON orders "
				+ "FOR EACH ROW EXECUTE FUNCTION test_correction_slow_write()");
	}

	private void dropSlowdowns() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_correction_slow_stock_trigger ON parts");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_correction_slow_order_trigger ON orders");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_correction_slow_write()");
	}

	// ---- fixtures -----------------------------------------------------

	private void seedAccount(String email, Role role) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	/**
	 * Logs in and returns a fresh session. Only one MANAGER account may exist,
	 * so a race between two manager-only routes uses two separate logins of
	 * that account rather than one session shared across threads.
	 */
	private MockHttpSession login(String email) throws Exception {
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

	private Long seedBomLine(Long project, Long part, int quantityPerUnit) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?) RETURNING id",
				Long.class, project, part, quantityPerUnit));
	}

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

	/**
	 * A HIGH order holding the part's whole stock of 10 (required 10), taken
	 * by a real 1-unit pick through the endpoint: stock 9, line reserved 9 /
	 * picked 1 — a state the real actions produce.
	 */
	private Long[] seedTakenOrder(MockHttpSession technician, Long partId) throws Exception {
		Long order = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(7), Instant.now().minusSeconds(60));
		Long line = seedOrderLine(order, partId, 10, 10);
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", order, line).session(technician)
			.with(csrf())
			.param("quantity", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + order));
		return new Long[] { order, line };
	}

	private MvcResult postPick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity))).andReturn();
	}

	private MvcResult postReceipt(MockHttpSession session, Long partId, int quantity) throws Exception {
		return mockMvc.perform(post("/deliveries").session(session)
			.with(csrf())
			.param("rows", "5")
			.param("partId0", partId.toString())
			.param("quantity0", Integer.toString(quantity))).andReturn();
	}

	private MvcResult postAdjust(MockHttpSession session, Long partId, int delta) throws Exception {
		return mockMvc.perform(post("/parts/{id}/correction/adjust", partId).session(session)
			.with(csrf())
			.param("delta", Integer.toString(delta))
			.param("reason", "Inwentaryzacja")).andReturn();
	}

	private MvcResult postSet(MockHttpSession session, Long partId, int newQuantity, int expectedQuantity)
			throws Exception {
		return mockMvc.perform(post("/parts/{id}/correction/set", partId).session(session)
			.with(csrf())
			.param("newQuantity", Integer.toString(newQuantity))
			.param("expectedQuantity", Integer.toString(expectedQuantity))
			.param("reason", "Inwentaryzacja")).andReturn();
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private Long lineOf(Long orderId, Long partId) {
		return jdbcTemplate.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND part_id = ?",
				Long.class, orderId, partId);
	}

	private List<Map<String, Object>> correctionsOf(Long partId) {
		return jdbcTemplate.queryForList(
				"SELECT quantity_before, quantity_after FROM stock_corrections WHERE part_id = ? ORDER BY id", partId);
	}

	/**
	 * The part's invariants, as SQL over {@code parts}/{@code order_lines}:
	 * stock is non-negative; the live reservations of the part's OPEN orders
	 * (net of picks, since a pick moves units from reserved to picked and
	 * lowers stock by the same amount) never exceed its stock; and no line
	 * holds more than it requires.
	 */
	private void assertInvariants(Long partId, int iteration) {
		int stock = stockOf(partId);
		Integer totalReserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(l.reserved_quantity), 0) FROM order_lines l JOIN orders o ON o.id = l.order_id "
						+ "WHERE l.part_id = ? AND o.status = 'OPEN'",
				Integer.class, partId);
		Integer overfilledLines = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM order_lines WHERE part_id = ? AND picked_quantity + reserved_quantity > required_quantity",
				Integer.class, partId);
		assertThat(stock).as("iteration %d: stock", iteration).isNotNegative();
		assertThat(totalReserved).as("iteration %d: reserved vs stock", iteration).isLessThanOrEqualTo(stock);
		assertThat(overfilledLines).as("iteration %d: lines over required", iteration).isZero();
	}

	/**
	 * Exactly one {@code stock_corrections} row, written against the stock
	 * the correction actually locked: {@code before} is one of
	 * {@code possibleBefores} and {@code after} is what the correction made
	 * of it.
	 */
	private void assertOneCorrection(Long partId, int iteration, IntUnaryOperator afterOf,
			Integer... possibleBefores) {
		List<Map<String, Object>> rows = correctionsOf(partId);
		assertThat(rows).as("iteration %d: correction rows", iteration).hasSize(1);
		int before = ((Number) rows.get(0).get("quantity_before")).intValue();
		int after = ((Number) rows.get(0).get("quantity_after")).intValue();
		assertThat(before).as("iteration %d: correction before", iteration).isIn((Object[]) possibleBefores);
		assertThat(after).as("iteration %d: correction after", iteration).isEqualTo(afterOf.applyAsInt(before));
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

	private static Callable<MvcResult> delayed(long delayMillis, Callable<MvcResult> request) {
		return () -> {
			Thread.sleep(delayMillis);
			return request.call();
		};
	}

	private static boolean succeeded(MvcResult result) {
		return result.getResponse().getStatus() == 302;
	}

	private static void assertCorrectionSucceeded(MvcResult result, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: correction status", iteration)
			.isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).as("iteration %d: correction redirect", iteration)
			.isEqualTo("/parts");
	}

	private static void assertCorrectionRejected(MvcResult result, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: correction status", iteration)
			.isEqualTo(200);
		assertThat(result.getModelAndView().getViewName()).as("iteration %d: correction view", iteration)
			.isEqualTo(StockCorrectionController.VIEW);
	}

	private static void assertPickSucceeded(MvcResult result, Long orderId, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: pick status", iteration).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).as("iteration %d: pick redirect", iteration)
			.isEqualTo("/picking/" + orderId);
	}

	private static void assertPickRejected(MvcResult result, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: pick status", iteration).isEqualTo(200);
	}

	/** Alternates both starting together and each one arriving while the other is inside its slowed write. */
	private static long firstDelay(int iteration) {
		return iteration % 3 == 1 ? 50 : 0;
	}

	private static long secondDelay(int iteration) {
		return iteration % 3 == 2 ? 50 : 0;
	}

	// ---- tests ----------------------------------------------------------

	/**
	 * The manager adjusts a part by -4 while a technician receives +10 of it.
	 * Each write locks the part and computes from the locked stock, so neither
	 * update is lost: stock 30 + 10 - 4 = 36, the correction row records the
	 * stock it actually saw (30 or 40), and the waiting order larger than the
	 * stock is reallocated from the final 36.
	 */
	@Test
	void adjustRacingADeliveryNeverLosesAnUpdate() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		MockHttpSession manager = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Adjust Receipt Part " + iteration, 30);
			Long waitingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long waitingLine = seedOrderLine(waitingOrder, partId, 100, 30);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postAdjust(manager, partId, -4)),
					delayed(secondDelay(it), () -> postReceipt(technician, partId, 10)));

			assertCorrectionSucceeded(results[0], iteration);
			assertThat(results[1].getResponse().getStatus()).as("iteration %d: receipt status", iteration)
				.isEqualTo(302);
			assertThat(results[1].getResponse().getRedirectedUrl()).as("iteration %d: receipt redirect", iteration)
				.isEqualTo("/parts");
			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(36);
			assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isEqualTo(36);
			assertOneCorrection(partId, iteration, before -> before - 4, 30, 40);
		}
	}

	/**
	 * The manager adjusts a part by -2 while a technician picks 3 from the
	 * taken HIGH order holding all of it (stock 9, reserved 9, picked 1). Both
	 * succeed in either order and the result is the same: stock
	 * 9 - 3 - 2 = 4, the taken line picked 4 / reserved 4 — when the
	 * adjustment lands first, its deficit pass shrinks the taken reservation
	 * to 7 and the pick draws 3 of those; when the pick lands first, the
	 * adjustment shrinks the remaining 6 to the new stock of 4.
	 */
	@Test
	void adjustDownRacingAPickNeverLosesAnUpdate() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		MockHttpSession manager = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Adjust Pick Part " + iteration, 10);
			Long[] taken = seedTakenOrder(technician, partId);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postAdjust(manager, partId, -2)),
					delayed(secondDelay(it), () -> postPick(technician, taken[0], taken[1], 3)));

			assertCorrectionSucceeded(results[0], iteration);
			assertPickSucceeded(results[1], taken[0], iteration);
			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(4);
			assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(4);
			assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(4);
			assertOneCorrection(partId, iteration, before -> before - 2, 9, 6);
		}
	}

	/**
	 * The manager adjusts a part by -8 while a technician picks 3 from the
	 * taken order holding all of it (stock 9, reserved 9, picked 1). Exactly
	 * one of them can succeed, decided by the stock it locks: an adjustment
	 * landing first leaves stock 1 and shrinks the reservation to 1, so the
	 * pick of 3 is rejected; a pick landing first leaves stock 6, so the
	 * adjustment would go below zero and is rejected with nothing written.
	 */
	@Test
	void adjustDownRacingAPickRejectsWhicheverWouldBreakTheStock() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		MockHttpSession manager = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Adjust Below Zero Part " + iteration, 10);
			Long[] taken = seedTakenOrder(technician, partId);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postAdjust(manager, partId, -8)),
					delayed(secondDelay(it), () -> postPick(technician, taken[0], taken[1], 3)));

			assertInvariants(partId, iteration);
			assertThat(succeeded(results[0])).as("iteration %d: exactly one succeeds", iteration)
				.isNotEqualTo(succeeded(results[1]));
			if (succeeded(results[0])) {
				assertCorrectionSucceeded(results[0], iteration);
				assertPickRejected(results[1], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(1);
				assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(1);
				assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(1);
				assertOneCorrection(partId, iteration, before -> before - 8, 9);
			}
			else {
				assertCorrectionRejected(results[0], iteration);
				assertPickSucceeded(results[1], taken[0], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(6);
				assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(4);
				assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(6);
				assertThat(correctionsOf(partId)).as("iteration %d: correction rows", iteration).isEmpty();
			}
		}
	}

	/**
	 * The manager sets a part's counted total to 12 (from the 9 the form
	 * showed) while a technician picks 3 from the taken HIGH order holding all
	 * 9 units; a LOW order waits for 20. Never a silent overwrite of the pick:
	 * either the set lands first (stock 12, the 3 new units go to the waiting
	 * order, the taken order is already full) and the pick then draws from the
	 * taken reservation — stock 9, taken picked 4 / reserved 6, waiting 3, one
	 * correction 9 → 12 — or the pick lands first and the set is rejected as
	 * stale with nothing written — stock 6, taken picked 4 / reserved 6,
	 * waiting 0.
	 */
	@Test
	void setRacingAPickIsEitherAppliedBeforeThePickOrRejectedAsStale() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		MockHttpSession manager = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Set Pick Part " + iteration, 10);
			Long[] taken = seedTakenOrder(technician, partId);
			Long waitingOrder = seedOrder(projectId, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
			Long waitingLine = seedOrderLine(waitingOrder, partId, 20, 0);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postSet(manager, partId, 12, 9)),
					delayed(secondDelay(it), () -> postPick(technician, taken[0], taken[1], 3)));

			assertInvariants(partId, iteration);
			assertPickSucceeded(results[1], taken[0], iteration);
			assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(4);
			assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(6);
			if (succeeded(results[0])) {
				assertCorrectionSucceeded(results[0], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(9);
				assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isEqualTo(3);
				assertOneCorrection(partId, iteration, before -> 12, 9);
			}
			else {
				assertCorrectionRejected(results[0], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(6);
				assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isZero();
				assertThat(correctionsOf(partId)).as("iteration %d: correction rows", iteration).isEmpty();
			}
		}
	}

	/**
	 * The manager sets a part's counted total down to 2 (from the 9 the form
	 * showed, all reserved by the taken order) while a technician picks 3
	 * from that order. Exactly one succeeds and the pick never exceeds the
	 * post-correction reservation: a set landing first shrinks the taken
	 * reservation to 2 (the deficit pass), so the pick of 3 is rejected —
	 * stock 2, picked 1 / reserved 2, one correction 9 → 2; a pick landing
	 * first leaves stock 6, so the set is rejected as stale — stock 6,
	 * picked 4 / reserved 6, no correction.
	 */
	@Test
	void setDownIntoATakenDeficitRacingAPickNeverPicksPastTheCorrectedReservation() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		MockHttpSession manager = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Set Deficit Part " + iteration, 10);
			Long[] taken = seedTakenOrder(technician, partId);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postSet(manager, partId, 2, 9)),
					delayed(secondDelay(it), () -> postPick(technician, taken[0], taken[1], 3)));

			assertInvariants(partId, iteration);
			assertThat(succeeded(results[0])).as("iteration %d: exactly one succeeds", iteration)
				.isNotEqualTo(succeeded(results[1]));
			if (succeeded(results[0])) {
				assertCorrectionSucceeded(results[0], iteration);
				assertPickRejected(results[1], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(2);
				assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(1);
				assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(2);
				assertOneCorrection(partId, iteration, before -> 2, 9);
			}
			else {
				assertCorrectionRejected(results[0], iteration);
				assertPickSucceeded(results[1], taken[0], iteration);
				assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(6);
				assertThat(pickedOf(taken[1])).as("iteration %d: taken picked", iteration).isEqualTo(4);
				assertThat(reservedOf(taken[1])).as("iteration %d: taken reserved", iteration).isEqualTo(6);
				assertThat(correctionsOf(partId)).as("iteration %d: correction rows", iteration).isEmpty();
			}
		}
	}

	/**
	 * The manager adjusts a part by -4 while (in a second login) creating a
	 * HIGH order (BOM 8 per unit) on it; a NORMAL order already holds 5 of
	 * the 10 units. Both events rebuild the non-taken allocation from the
	 * stock they see under the part lock, so whichever commits last decides
	 * it from the final stock of 6: the new HIGH order is granted
	 * min(8, 6) = 6 and the existing order 0 — the same result in either
	 * order.
	 */
	@Test
	void adjustDownRacingANewOrderLeavesTheAllocationTheRulesGrantFromTheFinalStock() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession correcting = login(MANAGER_EMAIL);
		MockHttpSession ordering = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long orderProject = seedProject("Correction Order Race Board " + iteration);
			Long partId = seedPart("Adjust Order Part " + iteration, 10);
			seedBomLine(orderProject, partId, 8);
			Long existingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7),
					Instant.now().minusSeconds(60));
			Long existingLine = seedOrderLine(existingOrder, partId, 5, 5);
			int it = iteration;

			MvcResult[] results = race(delayed(firstDelay(it), () -> postAdjust(correcting, partId, -4)),
					delayed(secondDelay(it), () -> mockMvc.perform(post("/orders").session(ordering)
						.with(csrf())
						.param("projectId", orderProject.toString())
						.param("quantityUnits", "1")
						.param("priority", Priority.HIGH.name())
						.param("requiredDate", LocalDate.now().plusDays(7).toString())).andReturn()));

			assertCorrectionSucceeded(results[0], iteration);
			MvcResult createResult = results[1];
			assertThat(createResult.getResponse().getStatus()).as("iteration %d: create status", iteration)
				.isEqualTo(302);
			String location = createResult.getResponse().getRedirectedUrl();
			assertThat(location).as("iteration %d: create redirect", iteration).startsWith("/orders/");
			Long newLine = lineOf(Long.valueOf(location.substring("/orders/".length())), partId);

			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(6);
			assertThat(reservedOf(newLine)).as("iteration %d: new order reserved", iteration).isEqualTo(6);
			assertThat(reservedOf(existingLine)).as("iteration %d: existing order reserved", iteration).isZero();
			assertOneCorrection(partId, iteration, before -> before - 4, 10);
		}
	}

}

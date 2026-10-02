package pl.regavio.stockahead.parts;

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
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@code DeliveryController}'s receipt write can't lose an update and
 * can't break the stock/reservation invariants (AGENTS.md: stock never below
 * zero, no unit reserved twice) when it races another receipt, a pick, or the
 * creation of a new order on the same part, against real Postgres via
 * Testcontainers. Mirrors the sibling {@code orders} concurrency tests: raw
 * SQL fixtures, the real HTTP routes through {@code MockMvc} with logged-in
 * {@link MockHttpSession}s, two threads released together by a
 * {@link CountDownLatch}, and throwaway triggers that widen the windows.
 *
 * <p>
 * A {@code pg_sleep} trigger on every stock-changing {@code UPDATE} of
 * {@code parts} holds each request inside its read-to-commit window, so
 * without the part-row lock the two transactions would both read the old
 * stock and the second write would overwrite the first. A second trigger
 * slows the {@code INSERT} of a new order the same way. Each race is repeated
 * with alternating start offsets. Every scenario's final state is
 * independent of which request wins the lock, since a pick never shrinks
 * another order's reservation and each receipt or order creation rebuilds the
 * non-taken allocation from the then-current stock — so the expected values
 * are asserted exactly, alongside the invariants written as SQL over
 * {@code parts}/{@code order_lines}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "delivery-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "delivery-concurrency-manager@example.com";

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
		projectId = seedProject("Delivery Concurrency Board");
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropSlowdowns();
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
	 * A stock change on {@code parts} (a receipt's increment or a pick's
	 * decrement) and a new {@code orders} row each sleep before landing, so
	 * the writing transaction stays open well past the moment a competing
	 * request reads the same part.
	 */
	private void installSlowdowns() {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_delivery_slow_write() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.2);
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_delivery_slow_stock_trigger BEFORE UPDATE ON parts "
				+ "FOR EACH ROW WHEN (NEW.quantity IS DISTINCT FROM OLD.quantity) "
				+ "EXECUTE FUNCTION test_delivery_slow_write()");
		jdbcTemplate.execute("CREATE TRIGGER test_delivery_slow_order_trigger BEFORE INSERT ON orders "
				+ "FOR EACH ROW EXECUTE FUNCTION test_delivery_slow_write()");
	}

	private void dropSlowdowns() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_delivery_slow_stock_trigger ON parts");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_delivery_slow_order_trigger ON orders");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_delivery_slow_write()");
	}

	// ---- fixtures -----------------------------------------------------

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

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));
	}

	private MvcResult postReceipt(MockHttpSession session, Long partId, int quantity) throws Exception {
		return mockMvc.perform(post("/deliveries").session(session)
			.with(csrf())
			.param("rows", "5")
			.param("partId0", partId.toString())
			.param("quantity0", Integer.toString(quantity))).andReturn();
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

	/**
	 * The part's invariants, as SQL over {@code parts}/{@code order_lines}:
	 * stock is non-negative; the live reservations on the part (net of picks,
	 * since a pick moves units from reserved to picked and lowers stock by the
	 * same amount) never exceed its stock; and no line holds more than it
	 * requires.
	 */
	private void assertInvariants(Long partId, int iteration) {
		int stock = stockOf(partId);
		Integer totalReserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				partId);
		Integer overfilledLines = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM order_lines WHERE part_id = ? AND picked_quantity + reserved_quantity > required_quantity",
				Integer.class, partId);
		assertThat(stock).as("iteration %d: stock", iteration).isNotNegative();
		assertThat(totalReserved).as("iteration %d: reserved vs stock", iteration).isLessThanOrEqualTo(stock);
		assertThat(overfilledLines).as("iteration %d: lines over required", iteration).isZero();
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

	private static void assertReceiptSucceeded(MvcResult result, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: receipt status", iteration).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).as("iteration %d: receipt redirect", iteration)
			.isEqualTo("/parts");
	}

	// ---- tests ----------------------------------------------------------

	/**
	 * Two technicians each receive +10 of the same part at once. The slowed
	 * stock write keeps the first receipt's transaction open while the second
	 * one reads the part, so only the part-row lock keeps the second from
	 * computing its total from the stale stock. Final stock is the start
	 * value + 20, and a waiting order larger than the stock was reallocated
	 * from the stock that includes both deliveries.
	 */
	@Test
	void twoConcurrentReceiptsOnTheSamePartNeverLoseAnUpdate() throws Exception {
		MockHttpSession first = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession second = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Receipt Race Part " + iteration, 30);
			Long waitingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long waitingLine = seedOrderLine(waitingOrder, partId, 100, 30);
			long secondDelayMillis = iteration % 2 == 1 ? 50 : 0;

			MvcResult[] results = race(() -> postReceipt(first, partId, 10), () -> {
				Thread.sleep(secondDelayMillis);
				return postReceipt(second, partId, 10);
			});

			assertReceiptSucceeded(results[0], iteration);
			assertReceiptSucceeded(results[1], iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(50);
			assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reservation", iteration).isEqualTo(50);
			assertInvariants(partId, iteration);
		}
	}

	/**
	 * A receipt of +10 races a pick of 3 on the same part, from a taken HIGH
	 * order (required 10, one unit already picked through the real endpoint)
	 * that a LOW order is also waiting on. Whichever commits first, the pick
	 * draws from the taken order's own reservation (never shrunk by the
	 * receipt's reallocation) and the receipt tops the taken order up to its
	 * unmet remainder before the waiting order gets the rest: stock
	 * 5 - 1 + 10 - 3 = 11, taken line picked 4 / reserved 6, waiting line 5.
	 */
	@Test
	void receiptRacingAPickOnATakenOrderKeepsStockAndReservationsConsistent() throws Exception {
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);
		Instant now = Instant.now();

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long partId = seedPart("Pick Race Part " + iteration, 5);
			Long takenOrder = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(7), now.minusSeconds(60));
			Long takenLine = seedOrderLine(takenOrder, partId, 10, 5);
			Long waitingOrder = seedOrder(projectId, Priority.LOW, LocalDate.now().plusDays(7), now);
			Long waitingLine = seedOrderLine(waitingOrder, partId, 20, 0);
			pick(technician, takenOrder, takenLine, 1);
			// Alternate between both starting together and each one arriving while
			// the other is already inside its (slowed) stock write.
			long pickDelayMillis = iteration % 3 == 1 ? 50 : 0;
			long receiptDelayMillis = iteration % 3 == 2 ? 50 : 0;

			MvcResult[] results = race(() -> {
				Thread.sleep(pickDelayMillis);
				return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", takenOrder, takenLine)
					.session(technician)
					.with(csrf())
					.param("quantity", "3")).andReturn();
			}, () -> {
				Thread.sleep(receiptDelayMillis);
				return postReceipt(manager, partId, 10);
			});

			assertThat(results[0].getResponse().getStatus()).as("iteration %d: pick status", iteration)
				.isEqualTo(302);
			assertReceiptSucceeded(results[1], iteration);
			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(11);
			assertThat(pickedOf(takenLine)).as("iteration %d: taken picked", iteration).isEqualTo(4);
			assertThat(reservedOf(takenLine)).as("iteration %d: taken reserved", iteration).isEqualTo(6);
			assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isEqualTo(5);
		}
	}

	/**
	 * A receipt of +5 races the manager creating a HIGH order (BOM 8 per
	 * unit) on a part a NORMAL order already holds 5 of. Both events rebuild
	 * the non-taken allocation from the stock they see under the part lock,
	 * so whichever commits last decides it from the final stock of 10: the
	 * new HIGH order is granted min(8, 10) = 8 and the existing order the
	 * remaining 2 — the same result in either order.
	 */
	@Test
	void receiptRacingANewOrderLeavesTheAllocationTheRulesGrantFromTheFinalStock() throws Exception {
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			Long orderProject = seedProject("Order Race Board " + iteration);
			Long partId = seedPart("Order Race Part " + iteration, 5);
			seedBomLine(orderProject, partId, 8);
			Long existingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7),
					Instant.now().minusSeconds(60));
			Long existingLine = seedOrderLine(existingOrder, partId, 5, 5);
			long createDelayMillis = iteration % 3 == 1 ? 50 : 0;
			long receiptDelayMillis = iteration % 3 == 2 ? 50 : 0;

			MvcResult[] results = race(() -> {
				Thread.sleep(createDelayMillis);
				return mockMvc.perform(post("/orders").session(manager)
					.with(csrf())
					.param("projectId", orderProject.toString())
					.param("quantityUnits", "1")
					.param("priority", Priority.HIGH.name())
					.param("requiredDate", LocalDate.now().plusDays(7).toString())).andReturn();
			}, () -> {
				Thread.sleep(receiptDelayMillis);
				return postReceipt(technician, partId, 5);
			});

			MvcResult createResult = results[0];
			assertThat(createResult.getResponse().getStatus()).as("iteration %d: create status", iteration)
				.isEqualTo(302);
			assertReceiptSucceeded(results[1], iteration);
			String location = createResult.getResponse().getRedirectedUrl();
			assertThat(location).as("iteration %d: create redirect", iteration).startsWith("/orders/");
			Long newOrder = Long.valueOf(location.substring("/orders/".length()));
			Long newLine = lineOf(newOrder, partId);

			assertInvariants(partId, iteration);
			int stock = stockOf(partId);
			assertThat(stock).as("iteration %d: stock", iteration).isEqualTo(10);
			int expectedNew = Math.min(8, stock);
			assertThat(reservedOf(newLine)).as("iteration %d: new order reserved", iteration)
				.isEqualTo(expectedNew);
			assertThat(reservedOf(existingLine)).as("iteration %d: existing order reserved", iteration)
				.isEqualTo(Math.min(5, stock - expectedNew));
		}
	}

}

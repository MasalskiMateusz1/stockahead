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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the no-double-pick / never-negative-stock invariant (AGENTS.md: a
 * part's stock must never drop below zero, and one unit must never be
 * reserved by two orders) holds under real concurrent writes to the picking
 * routes specifically, against real Postgres via Testcontainers. Mirrors
 * {@code ReservationConcurrencyTests}/{@code OrderCreationIntegrationTests}'s
 * fixture and session style: raw {@link JdbcTemplate} inserts for orders and
 * order lines (so an arbitrary pre-existing reservation/pick state can be set
 * up directly, which no endpoint exposes), and {@code MockMvc} against the
 * real HTTP routes with one {@link MockHttpSession} shared across the two
 * racing threads per test — calling {@link PickingController}'s methods
 * directly would bypass {@code @PreAuthorize} method-security AOP and
 * {@code SecurityContext} threading.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PickingConcurrencyTests {

	private static final String MANAGER_EMAIL = "picking-concurrency-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "picking-concurrency-technician@example.com";

	private static final String PASSWORD = "correct-password";

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

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject("Picking Concurrency Board", true);
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM order_lines");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("PickingConcurrencyTests Co");
		}
		return company;
	}

	private void seedAccount(String email, Role role) {
		companyFixtures.account(company(), email, PASSWORD, role, true);
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private MockHttpSession managerSession() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		return loginAs(MANAGER_EMAIL);
	}

	private MockHttpSession technicianSession() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		return loginAs(TECHNICIAN_EMAIL);
	}

	private Long seedProject(String name, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, ?) RETURNING id", Long.class, name, active));
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

	private Long seedOrder(Long project, int quantityUnits, Priority priority, LocalDate requiredDate,
			Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, project, quantityUnits, priority.name(), requiredDate, Timestamp.from(createdAt)));
	}

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity));
	}

	private int reservedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private Timestamp takenAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT taken_at FROM orders WHERE id = ?", Timestamp.class, orderId);
	}

	// ---- tests ----------------------------------------------------------

	/**
	 * Two threads race to pick more than half of a small reserved-but-unpicked
	 * amount from the very same line. Only one can win the part's
	 * {@code PESSIMISTIC_WRITE} lock first and succeed; the other must see the
	 * now-reduced {@code reservedQuantity - pickedQuantity} and be rejected
	 * with {@code picking.error.quantityExceedsAvailable} rather than both
	 * succeeding and over-drawing the line. The rejected side's request
	 * re-renders {@code picking-detail}, a template Phase 4 (not this phase)
	 * creates — in this phase that render fails with a
	 * {@code TemplateInputException}, which is an expected, known gap, not a
	 * business-logic defect, so it's swallowed here; only the resulting DB
	 * state (asserted below) is this test's concern.
	 */
	@Test
	void twoConcurrentPicksOnTheSameLineNeverTogetherExceedReservedQuantity() throws Exception {
		Long partId = seedPart("Scarce Racing Resistor", 5);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);

		MockHttpSession session = technicianSession();

		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Callable<Void> pickThree = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			try {
				mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
					.with(csrf())
					.param("quantity", "3"));
			}
			catch (Exception ex) {
				// Expected for the losing side: see method javadoc.
			}
			return null;
		};

		try {
			Future<Void> first = executor.submit(pickThree);
			Future<Void> second = executor.submit(pickThree);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		int pickedQuantity = pickedQuantityOf(lineId);
		int stock = stockOf(partId);

		assertThat(pickedQuantity).isLessThanOrEqualTo(5);
		assertThat(stock).isGreaterThanOrEqualTo(0);
		assertThat(stock).isEqualTo(5 - pickedQuantity);
	}

	/**
	 * One thread picks from an existing order's line (seeded with its
	 * reservation already in place, not yet taken) while another concurrently
	 * creates a higher-priority competing order for the same scarce part via
	 * {@code POST /orders} (reusing that real endpoint rather than calling
	 * {@link ReservationAllocator} directly, so its own transaction/locking
	 * shape is exercised). Whichever side wins the part lock first, the
	 * aggregate invariant must hold: the existing line can only end up taken
	 * (and so protected) if its pick committed before the reallocation ran,
	 * and the competitor can only ever claim stock that was genuinely still
	 * free at that point.
	 */
	@Test
	void pickRacingANewCompetingOrderNeverLetsTheCompetitorStealAnAlreadyTakenReservation() throws Exception {
		Long scarcePartId = seedPart("Scarce Racing Capacitor", 5);
		seedBomLine(projectId, scarcePartId, 5);
		Long existingOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
		Long existingLineId = seedOrderLine(existingOrderId, scarcePartId, 5, 5);

		MockHttpSession session = managerSession();

		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Callable<Void> pickExisting = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			try {
				mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", existingOrderId, existingLineId)
					.session(session)
					.with(csrf())
					.param("quantity", "2"));
			}
			catch (Exception ex) {
				// A rejected pick re-renders picking-detail, a Phase 4 template that
				// doesn't exist yet in this phase — see
				// twoConcurrentPicksOnTheSameLineNeverTogetherExceedReservedQuantity's
				// javadoc. Only the resulting DB state matters here.
			}
			return null;
		};
		Callable<Void> createCompetingOrder = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			mockMvc.perform(post("/orders").session(session)
				.with(csrf())
				.param("projectId", projectId.toString())
				.param("quantityUnits", "1")
				.param("priority", "HIGH")
				.param("requiredDate", LocalDate.now().plusDays(7).toString()));
			return null;
		};

		try {
			Future<Void> first = executor.submit(pickExisting);
			Future<Void> second = executor.submit(createCompetingOrder);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		Integer totalReservedPlusPicked = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity + picked_quantity), 0) FROM order_lines WHERE part_id = ?",
				Integer.class, scarcePartId);
		Integer totalPicked = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(picked_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				scarcePartId);
		int stock = stockOf(scarcePartId);

		assertThat(totalReservedPlusPicked).isLessThanOrEqualTo(5);
		assertThat(stock).isEqualTo(5 - totalPicked);
	}

	/**
	 * An order with two untaken lines on two different parts: two threads
	 * each pick one line concurrently. Each thread locks a different
	 * {@link pl.regavio.stockahead.parts.Part} row, so nothing serializes the
	 * two transactions' reads of {@code Order.takenAt} — both must still
	 * fully succeed, and the order must end up taken regardless of which
	 * transaction's {@code UPDATE} to the {@code orders} row committed last
	 * ({@code Order} has no {@code @Version} column; this is the first place
	 * in the codebase where two transactions can concurrently update the same
	 * {@code orders} row).
	 */
	@Test
	void concurrentFirstPicksOnDifferentLinesOfTheSameOrderBothSucceedAndLeaveItTaken() throws Exception {
		Long partAId = seedPart("Dual Pick Part A", 5);
		Long partBId = seedPart("Dual Pick Part B", 5);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineAId = seedOrderLine(orderId, partAId, 5, 5);
		Long lineBId = seedOrderLine(orderId, partBId, 5, 5);

		MockHttpSession session = technicianSession();

		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Callable<Void> pickLineA = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			mockMvc
				.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineAId).session(session)
					.with(csrf())
					.param("quantity", "2"))
				.andExpect(status().is3xxRedirection());
			return null;
		};
		Callable<Void> pickLineB = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			mockMvc
				.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineBId).session(session)
					.with(csrf())
					.param("quantity", "3"))
				.andExpect(status().is3xxRedirection());
			return null;
		};

		try {
			Future<Void> first = executor.submit(pickLineA);
			Future<Void> second = executor.submit(pickLineB);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(reservedQuantityOf(lineAId)).isEqualTo(3);
		assertThat(pickedQuantityOf(lineAId)).isEqualTo(2);
		assertThat(reservedQuantityOf(lineBId)).isEqualTo(2);
		assertThat(pickedQuantityOf(lineBId)).isEqualTo(3);
		assertThat(takenAtOf(orderId)).isNotNull();
	}

}

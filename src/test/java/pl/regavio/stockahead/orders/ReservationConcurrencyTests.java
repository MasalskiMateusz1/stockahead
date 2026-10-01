package pl.regavio.stockahead.orders;

import java.time.LocalDate;
import java.util.Set;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the no-double-reservation / never-negative-stock invariant under
 * real concurrent writes against real Postgres — the pattern AGENTS.md and
 * the roadmap's {@code F-01} call for, which didn't exist anywhere in this
 * codebase before this test. Two threads race to insert a competing order
 * for the same scarce part and each call
 * {@link ReservationAllocator#reallocateForParts(Set)} inside its own
 * transaction (mirroring the shape {@code OrderController}'s eventual
 * {@code POST /orders} handler will use, per {@code ProjectBomController
 * .addLine}), genuinely exercising
 * {@code PartRepository.findByIdInForUpdate(ids)}'s {@code PESSIMISTIC_WRITE}
 * row lock on Postgres, scoped to just the given part ids. The HTTP-level
 * version of "two concurrent order-creation POSTs" is naturally re-covered
 * once {@code OrderController} exists (Phase 3).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationConcurrencyTests {

	@Autowired
	private ReservationAllocator reservationAllocator;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Long projectId;

	private Long partId;

	@BeforeEach
	void setUp() {
		cleanUp();
		projectId = jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class,
				"Concurrency Board");
		partId = jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class,
				"Scarce Resistor", 5);
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
	}

	@Test
	void twoConcurrentOrdersForScarcePartNeverTogetherReserveMoreThanStock() throws Exception {
		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Callable<Void> createCompetingOrderAndReallocate = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
			transactionTemplate.executeWithoutResult(status -> {
				Long orderId = jdbcTemplate.queryForObject(
						"INSERT INTO orders (project_id, quantity_units, priority, required_date) "
								+ "VALUES (?, 1, 'NORMAL', ?) RETURNING id",
						Long.class, projectId, LocalDate.now().plusDays(1));
				jdbcTemplate.update(
						"INSERT INTO order_lines (order_id, part_id, required_quantity) VALUES (?, ?, ?)", orderId,
						partId, 5);
				reservationAllocator.reallocateForParts(Set.of(partId));
			});
			return null;
		};

		try {
			Future<Void> first = executor.submit(createCompetingOrderAndReallocate);
			Future<Void> second = executor.submit(createCompetingOrderAndReallocate);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		Integer totalReserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				partId);
		Integer stock = jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class,
				partId);

		assertThat(totalReserved).isLessThanOrEqualTo(5);
		assertThat(stock).isEqualTo(5);
	}

}

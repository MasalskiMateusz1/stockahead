package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

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
 * Proves {@link ReservationAllocator#reallocateForParts(Set)} against
 * {@code context/foundation/prd.md} §Business Logic and US-01's own
 * acceptance example, without going through HTTP. Orders/parts are seeded
 * directly via {@link JdbcTemplate}, matching
 * {@code ProjectBomIntegrationTests}'s fixture style, against a real
 * Postgres via Testcontainers. {@code created_at} is always passed
 * explicitly (a few seconds apart where tie-breaking matters) since
 * concurrent inserts sharing the default {@code now()} could otherwise
 * collide at second granularity.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationAllocatorTests {

	@Autowired
	private ReservationAllocator reservationAllocator;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
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

	// ---- fixtures -----------------------------------------------------

	private Long seedProject(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, name));
	}

	private Long seedPart(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	private Long seedOrder(Long projectId, int quantityUnits, Priority priority, LocalDate requiredDate,
			Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, projectId, quantityUnits, priority.name(), requiredDate, Timestamp.from(createdAt)));
	}

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity) VALUES (?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity));
	}

	private int reservedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	/**
	 * {@link ReservationAllocator#reallocateForParts(Set)} locks the given
	 * parts via {@code PESSIMISTIC_WRITE}, which requires an active
	 * transaction — the same contract its real caller (a future
	 * {@code OrderController}) will satisfy via its own
	 * {@code TransactionTemplate} block.
	 */
	private void reallocate(Set<Long> partIds) {
		transactionTemplate.executeWithoutResult(status -> reservationAllocator.reallocateForParts(partIds));
	}

	// ---- tests ----------------------------------------------------------

	@Test
	void usOneExampleReservesAvailableStockAndLeavesRestMissing() {
		Long projectId = seedProject("Resistor Board");
		Long partId = seedPart("Resistor 10k", 6);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 10);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(lineId)).isEqualTo(6);
	}

	@Test
	void higherPriorityOrderIsServedFirstEvenIfCreatedLater() {
		Long projectId = seedProject("Priority Board");
		Long partId = seedPart("Scarce Part", 5);
		Instant earlier = Instant.now();
		Instant later = earlier.plusSeconds(10);
		Long lowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), earlier);
		Long lowLineId = seedOrderLine(lowOrderId, partId, 5);
		Long highOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7), later);
		Long highLineId = seedOrderLine(highOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(highLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(0);
	}

	@Test
	void earlierRequiredDateWinsWhenPriorityIsTied() {
		Long projectId = seedProject("Deadline Board");
		Long partId = seedPart("Scarce Part", 5);
		Instant createdAt = Instant.now();
		Long earlyOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(1), createdAt);
		Long earlyLineId = seedOrderLine(earlyOrderId, partId, 5);
		Long lateOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(10),
				createdAt.plusSeconds(5));
		Long lateLineId = seedOrderLine(lateOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(earlyLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(lateLineId)).isEqualTo(0);
	}

	@Test
	void olderOrderWinsWhenPriorityAndRequiredDateAreTied() {
		Long projectId = seedProject("Tiebreak Board");
		Long partId = seedPart("Scarce Part", 5);
		LocalDate sameDate = LocalDate.now().plusDays(3);
		Instant olderCreatedAt = Instant.now();
		Instant newerCreatedAt = olderCreatedAt.plusSeconds(5);
		Long olderOrderId = seedOrder(projectId, 1, Priority.NORMAL, sameDate, olderCreatedAt);
		Long olderLineId = seedOrderLine(olderOrderId, partId, 5);
		Long newerOrderId = seedOrder(projectId, 1, Priority.NORMAL, sameDate, newerCreatedAt);
		Long newerLineId = seedOrderLine(newerOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(olderLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(newerLineId)).isEqualTo(0);
	}

	@Test
	void newHigherPriorityOrderShrinksExistingLowerPriorityReservationOnRecompute() {
		Long projectId = seedProject("Preemption Board");
		Long partId = seedPart("Scarce Part", 5);
		Long lowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
		Long lowLineId = seedOrderLine(lowOrderId, partId, 5);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(5);

		Long highOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long highLineId = seedOrderLine(highOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(highLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(0);
	}

}

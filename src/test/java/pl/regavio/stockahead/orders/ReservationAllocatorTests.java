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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
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
 * Proves {@link ReservationAllocator#reallocateForParts(Set)} against
 * {@code context/foundation/prd.md} §Business Logic and US-01's own
 * acceptance example, without going through HTTP. Orders/parts are seeded
 * directly via {@link JdbcTemplate}, matching
 * {@code ProjectBomIntegrationTests}'s fixture style, against a real
 * Postgres via Testcontainers. {@code created_at} is always passed
 * explicitly (a few seconds apart where tie-breaking matters) since
 * concurrent inserts sharing the default {@code now()} could otherwise
 * collide at second granularity. An order becomes taken only through the
 * real pick endpoint, so a taken order's stock, reservation and pick counts
 * are exactly what the app produces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReservationAllocatorTests {

	private static final String TECHNICIAN_EMAIL = "reservation-allocator-technician@example.com";

	private static final String MANAGER_EMAIL = "reservation-allocator-manager@example.com";

	private static final String PASSWORD = "correct-password";

	@Autowired
	private ReservationAllocator reservationAllocator;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

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
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
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

	private int pickedQuantityOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private void setStock(Long partId, int quantity) {
		jdbcTemplate.update("UPDATE parts SET quantity = ? WHERE id = ?", quantity, partId);
	}

	/**
	 * Logs in as {@code email}, creating the account on first use only, so a
	 * test can pick (or report) more than once without colliding on the
	 * account's unique email.
	 */
	private MockHttpSession session(String email, Role role) throws Exception {
		transactionTemplate.executeWithoutResult(status -> {
			if (accountRepository.findByEmail(email).isPresent()) {
				return;
			}
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

	private MockHttpSession technicianSession() throws Exception {
		return session(TECHNICIAN_EMAIL, Role.TECHNICIAN);
	}

	/** Takes the order the only way the app can: a real pick through the picking endpoint. */
	private void pick(Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(technicianSession())
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	/** Reports a taken order's completion through the real picking endpoint. */
	private void reportCompletion(Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technicianSession())
			.with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT completion_reported_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, orderId))
			.isTrue();
	}

	/** Rejects a pending completion report through the real manager endpoint. */
	private void rejectCompletion(Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/reject-completion", orderId).session(session(MANAGER_EMAIL, Role.MANAGER))
			.with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT completion_reported_at IS NULL FROM orders WHERE id = ?", Boolean.class, orderId))
			.isTrue();
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

	@Test
	void takenOrdersReservationSurvivesANewHigherPriorityCompetitor() throws Exception {
		Long projectId = seedProject("Taken Order Board");
		Long partId = seedPart("Scarce Part", 5);
		Long lowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
		Long lowLineId = seedOrderLine(lowOrderId, partId, 5);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(5);
		pick(lowOrderId, lowLineId, 1);
		assertThat(stockOf(partId)).isEqualTo(4);

		Long highOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long highLineId = seedOrderLine(highOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(4);
		assertThat(pickedQuantityOf(lowLineId)).isEqualTo(1);
		assertThat(reservedQuantityOf(highLineId)).isEqualTo(0);
	}

	@Test
	void takenOrderWithPartialPickKeepsItsRemainingReservationProtected() throws Exception {
		Long projectId = seedProject("Partial Pick Board");
		Long partId = seedPart("Scarce Part", 4);
		Long takenOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long takenLineId = seedOrderLine(takenOrderId, partId, 10);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(4);
		pick(takenOrderId, takenLineId, 3);
		assertThat(stockOf(partId)).isEqualTo(1);

		Long competingOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long competingLineId = seedOrderLine(competingOrderId, partId, 4);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(1);
		assertThat(pickedQuantityOf(takenLineId)).isEqualTo(3);
		assertThat(reservedQuantityOf(competingLineId)).isEqualTo(0);
	}

	@Test
	void nonTakenOrdersStillCompeteNormallyAmongThemselves() throws Exception {
		Long projectId = seedProject("Mixed Competition Board");
		Long partId = seedPart("Scarce Part", 10);
		Instant takenCreatedAt = Instant.now();
		Long takenOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(1), takenCreatedAt);
		Long takenLineId = seedOrderLine(takenOrderId, partId, 5);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(5);
		pick(takenOrderId, takenLineId, 1);
		assertThat(stockOf(partId)).isEqualTo(9);

		Instant earlyCreatedAt = takenCreatedAt.plusSeconds(5);
		Long earlyOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(2), earlyCreatedAt);
		Long earlyLineId = seedOrderLine(earlyOrderId, partId, 5);
		Long lateOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(10),
				earlyCreatedAt.plusSeconds(5));
		Long lateLineId = seedOrderLine(lateOrderId, partId, 5);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(4);
		assertThat(reservedQuantityOf(earlyLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(lateLineId)).isEqualTo(0);
	}

	@Test
	void takenShortOrderReceivesNewlyAvailableUnits() throws Exception {
		Long projectId = seedProject("Top-Up Board");
		Long partId = seedPart("Scarce Part", 4);
		Long takenOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long takenLineId = seedOrderLine(takenOrderId, partId, 10);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(4);
		pick(takenOrderId, takenLineId, 2);
		assertThat(stockOf(partId)).isEqualTo(2);
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(2);

		// Stand-in for a future delivery or stock correction: 5 new units.
		setStock(partId, 7);
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(7);
		assertThat(pickedQuantityOf(takenLineId)).isEqualTo(2);
	}

	@Test
	void takenOrderTopUpIsCappedAtRequiredMinusPicked() throws Exception {
		Long projectId = seedProject("Top-Up Cap Board");
		Long partId = seedPart("Scarce Part", 6);
		Long takenOrderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long takenLineId = seedOrderLine(takenOrderId, partId, 10);

		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(6);
		pick(takenOrderId, takenLineId, 2);
		pick(takenOrderId, takenLineId, 3);
		assertThat(stockOf(partId)).isEqualTo(1);
		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(1);
		assertThat(pickedQuantityOf(takenLineId)).isEqualTo(5);

		// Far more stock than the line can ever use: 20 new units.
		setStock(partId, 21);
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(5);
		assertThat(pickedQuantityOf(takenLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(takenLineId) + pickedQuantityOf(takenLineId)).isLessThanOrEqualTo(10);

		// A second pass on the same row must not grow it again.
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(takenLineId)).isEqualTo(5);
		assertThat(pickedQuantityOf(takenLineId)).isEqualTo(5);
	}

	@Test
	void nonTakenHigherPriorityOrderGetsExtraUnitsBeforeTakenLowerPriorityOrder() throws Exception {
		Long projectId = seedProject("Top-Up Order Board");
		Long partId = seedPart("Scarce Part", 4);
		Long takenLowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
		Long takenLowLineId = seedOrderLine(takenLowOrderId, partId, 10);

		reallocate(Set.of(partId));
		pick(takenLowOrderId, takenLowLineId, 1);
		assertThat(stockOf(partId)).isEqualTo(3);
		assertThat(reservedQuantityOf(takenLowLineId)).isEqualTo(3);

		Long highOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long highLineId = seedOrderLine(highOrderId, partId, 5);
		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(highLineId)).isEqualTo(0);

		// 7 new units: HIGH takes its 5 first, the taken LOW order gets the last 2.
		setStock(partId, 10);
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(highLineId)).isEqualTo(5);
		assertThat(reservedQuantityOf(takenLowLineId)).isEqualTo(5);
		assertThat(pickedQuantityOf(takenLowLineId)).isEqualTo(1);
	}

	@Test
	void takenHigherPriorityOrderTopsUpBeforeNonTakenLowerPriorityOrder() throws Exception {
		Long projectId = seedProject("Top-Up Preemption Board");
		Long partId = seedPart("Scarce Part", 4);
		Long takenHighOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7), Instant.now());
		Long takenHighLineId = seedOrderLine(takenHighOrderId, partId, 10);

		reallocate(Set.of(partId));
		pick(takenHighOrderId, takenHighLineId, 1);
		assertThat(stockOf(partId)).isEqualTo(3);
		assertThat(reservedQuantityOf(takenHighLineId)).isEqualTo(3);

		// While HIGH's completion is reported it gets nothing extra, so new
		// units go to the non-taken LOW order.
		reportCompletion(takenHighOrderId);
		Long lowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long lowLineId = seedOrderLine(lowOrderId, partId, 5);
		setStock(partId, 7);
		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenHighLineId)).isEqualTo(3);
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(4);

		// Back to picking: reject reallocates by itself — HIGH tops up first,
		// LOW shrinks as normal preemption.
		rejectCompletion(takenHighOrderId);
		assertThat(reservedQuantityOf(takenHighLineId)).isEqualTo(7);
		assertThat(pickedQuantityOf(takenHighLineId)).isEqualTo(1);
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(0);

		// A second pass changes nothing.
		reallocate(Set.of(partId));
		assertThat(reservedQuantityOf(takenHighLineId)).isEqualTo(7);
		assertThat(pickedQuantityOf(takenHighLineId)).isEqualTo(1);
		assertThat(reservedQuantityOf(lowLineId)).isEqualTo(0);
	}

	@Test
	void reportedTakenOrderReceivesNoExtraUnits() throws Exception {
		Long projectId = seedProject("Reported Board");
		Long partId = seedPart("Scarce Part", 4);
		Long reportedOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7), Instant.now());
		Long reportedLineId = seedOrderLine(reportedOrderId, partId, 10);

		reallocate(Set.of(partId));
		pick(reportedOrderId, reportedLineId, 1);
		assertThat(reservedQuantityOf(reportedLineId)).isEqualTo(3);
		reportCompletion(reportedOrderId);

		Long otherOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long otherLineId = seedOrderLine(otherOrderId, partId, 2);
		setStock(partId, 8);
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(reportedLineId)).isEqualTo(3);
		assertThat(pickedQuantityOf(reportedLineId)).isEqualTo(1);
		assertThat(reservedQuantityOf(otherLineId)).isEqualTo(2);
	}

	@Test
	void takenOrderReservationIsNeverReducedByTopUpPass() throws Exception {
		Long projectId = seedProject("Floor Board");
		Long partId = seedPart("Scarce Part", 5);
		Long takenLowOrderId = seedOrder(projectId, 1, Priority.LOW, LocalDate.now().plusDays(7), Instant.now());
		Long takenLowLineId = seedOrderLine(takenLowOrderId, partId, 10);

		reallocate(Set.of(partId));
		pick(takenLowOrderId, takenLowLineId, 1);
		assertThat(stockOf(partId)).isEqualTo(4);
		assertThat(reservedQuantityOf(takenLowLineId)).isEqualTo(4);

		Long highOrderId = seedOrder(projectId, 1, Priority.HIGH, LocalDate.now().plusDays(7),
				Instant.now().plusSeconds(10));
		Long highLineId = seedOrderLine(highOrderId, partId, 6);
		// 3 new units: not enough for HIGH, which must not reach into LOW's 4.
		setStock(partId, 7);
		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(highLineId)).isEqualTo(3);
		assertThat(reservedQuantityOf(takenLowLineId)).isEqualTo(4);

		reallocate(Set.of(partId));

		assertThat(reservedQuantityOf(highLineId)).isEqualTo(3);
		assertThat(reservedQuantityOf(takenLowLineId)).isEqualTo(4);
		assertThat(pickedQuantityOf(takenLowLineId)).isEqualTo(1);
	}

}

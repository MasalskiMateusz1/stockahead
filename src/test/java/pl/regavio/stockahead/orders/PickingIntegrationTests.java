package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code PickingController}'s pick action (Phase 4): a valid partial
 * pick shifting {@code reservedQuantity} to {@code pickedQuantity} and
 * decrementing {@code Part.quantity} while stamping {@code Order.takenAt} on
 * first pick only, rejection of an overpick and of a pick on a non-OPEN
 * order (both leaving DB state untouched), and a second partial pick on an
 * already-taken order succeeding without overwriting the original
 * {@code takenAt}. Mirrors {@code OrderCreationIntegrationTests}'s fixture
 * and session style and reuses {@code PickingConcurrencyTests}'s raw
 * {@code JdbcTemplate} seeding helpers for orders/lines, since no endpoint
 * exposes setting up an arbitrary pre-existing reservation/pick state.
 * Against a real Postgres via Testcontainers, deliberately not
 * {@code @Transactional} so the DB constraints genuinely fire.
 *
 * <p>
 * {@code secondPartialPickOnAnAlreadyTakenOrderSucceedsWithoutOverwritingTakenAt}
 * deliberately picks MORE THAN HALF of the line's reservation on the first
 * pick, then the remainder on the second: {@code maxPickable} must be
 * computed from the current (already-decremented) {@code reservedQuantity}
 * alone, not {@code reservedQuantity - pickedQuantity} — the latter
 * double-counts every prior pick and would wrongly reject this exact
 * sequence once more than half of a reservation has been picked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PickingIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "picking-action-technician@example.com";

	private static final String ORDER_NOT_OPEN_ERROR = "Zlecenie nie jest już otwarte.";

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
		projectId = seedProject("Picking Action Board", true);
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
	}

	// ---- fixtures -----------------------------------------------------

	private void seedAccount(String email, Role role) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode("correct-password"));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
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

	private void setOrderStatus(Long orderId, OrderStatus orderStatus) {
		transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", orderStatus.name(),
						orderId));
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

	// ---- happy path -----------------------------------------------------

	@Test
	void validPartialPickDecrementsStockShiftsReservedToPickedAndStampsTakenAt() throws Exception {
		Long partId = seedPart("Resistor 10k", 10);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);

		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", "3"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", matchesPattern("/picking/" + orderId)));

		assertThat(stockOf(partId)).isEqualTo(7);
		assertThat(reservedQuantityOf(lineId)).isEqualTo(2);
		assertThat(pickedQuantityOf(lineId)).isEqualTo(3);
		assertThat(takenAtOf(orderId)).isNotNull();
	}

	// ---- overpick -------------------------------------------------------

	@Test
	void overpickRequestIsRejectedAndLeavesStockAndLinesUnchanged() throws Exception {
		Long partId = seedPart("Scarce Capacitor", 10);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);

		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", "10"))
			.andExpect(status().isOk())
			.andExpect(content().string(
					containsString("Nie można pobrać więcej niż 5 szt. (tyle pozostało zarezerwowane).")));

		assertThat(stockOf(partId)).isEqualTo(10);
		assertThat(reservedQuantityOf(lineId)).isEqualTo(5);
		assertThat(pickedQuantityOf(lineId)).isEqualTo(0);
		assertThat(takenAtOf(orderId)).isNull();
	}

	// ---- order not open ---------------------------------------------------

	@Test
	void pickOnCancelledOrCompletedOrderIsRejectedAndLeavesStateUnchanged() throws Exception {
		MockHttpSession session = technicianSession();

		for (OrderStatus notOpenStatus : new OrderStatus[] { OrderStatus.CANCELLED, OrderStatus.COMPLETED }) {
			Long partId = seedPart("Part for " + notOpenStatus, 10);
			Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long lineId = seedOrderLine(orderId, partId, 5, 5);
			setOrderStatus(orderId, notOpenStatus);

			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
				.with(csrf())
				.param("quantity", "2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));

			assertThat(stockOf(partId)).isEqualTo(10);
			assertThat(reservedQuantityOf(lineId)).isEqualTo(5);
			assertThat(pickedQuantityOf(lineId)).isEqualTo(0);
		}
	}

	// ---- second pick on an already-taken order -----------------------------

	@Test
	void secondPartialPickOnAnAlreadyTakenOrderSucceedsWithoutOverwritingTakenAt() throws Exception {
		Long partId = seedPart("Dual Pick Resistor", 20);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 10, 10);

		MockHttpSession session = technicianSession();

		// First pick takes MORE THAN HALF of the reservation: with the buggy
		// maxPickable = reservedQuantity - pickedQuantity, the second pick below
		// (of the genuine remainder) would be wrongly rejected, since that
		// formula double-counts the first pick.
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", "7"))
			.andExpect(status().is3xxRedirection());

		Timestamp takenAtAfterFirstPick = takenAtOf(orderId);
		assertThat(takenAtAfterFirstPick).isNotNull();
		assertThat(reservedQuantityOf(lineId)).isEqualTo(3);

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", "3"))
			.andExpect(status().is3xxRedirection());

		assertThat(stockOf(partId)).isEqualTo(10);
		assertThat(reservedQuantityOf(lineId)).isEqualTo(0);
		assertThat(pickedQuantityOf(lineId)).isEqualTo(10);
		assertThat(takenAtOf(orderId)).isEqualTo(takenAtAfterFirstPick);
	}

}

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
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
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PickingIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "picking-action-technician@example.com";

	private static final String MANAGER_EMAIL = "picking-action-manager@example.com";

	private static final String ORDER_NOT_OPEN_ERROR = "Zlecenie nie jest już otwarte.";

	private static final String COMPLETION_REPORTED_ERROR =
			"Zlecenie zostało zgłoszone jako zakończone — pobieranie jest wstrzymane.";

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
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("PickingIntegrationTests Co");
		}
		return company;
	}

	private void seedAccount(String email, Role role) {
		companyFixtures.account(company(), email, "correct-password", role, true);
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

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	private void reportCompletion(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection());
	}

	private void confirmCompletion(MockHttpSession managerSession, Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/confirm-completion", orderId).session(managerSession).with(csrf()))
			.andExpect(status().is3xxRedirection());
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
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		for (OrderStatus notOpenStatus : new OrderStatus[] { OrderStatus.CANCELLED, OrderStatus.COMPLETED }) {
			Long partId = seedPart("Part for " + notOpenStatus, 10);
			Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long lineId = seedOrderLine(orderId, partId, 5, 5);
			if (notOpenStatus == OrderStatus.COMPLETED) {
				// Reach COMPLETED the way the app does: first pick, report, manager confirm.
				pick(session, orderId, lineId, 3);
				reportCompletion(session, orderId);
				confirmCompletion(manager, orderId);
			}
			else {
				// Reach CANCELLED the way the app does: the manager cancels the untaken order.
				mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager).with(csrf()))
					.andExpect(status().is3xxRedirection());
			}
			assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
				.isEqualTo(notOpenStatus.name());
			// A confirmed order keeps its 3 picked units and has the rest of its
			// reservation released; a cancelled untaken one has its whole
			// reservation released and stock untouched.
			boolean completed = notOpenStatus == OrderStatus.COMPLETED;
			int expectedStock = completed ? 7 : 10;
			int expectedReserved = 0;
			int expectedPicked = completed ? 3 : 0;

			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
				.with(csrf())
				.param("quantity", "2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));

			assertThat(stockOf(partId)).isEqualTo(expectedStock);
			assertThat(reservedQuantityOf(lineId)).isEqualTo(expectedReserved);
			assertThat(pickedQuantityOf(lineId)).isEqualTo(expectedPicked);
		}
	}

	// ---- completion reported -----------------------------------------------

	@Test
	void pickOnCompletionReportedOrderIsRejectedAndLeavesStockAndLinesUnchanged() throws Exception {
		Long partId = seedPart("Reported Order Resistor", 20);
		Long orderId = seedOrder(projectId, 1, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 10, 10);

		MockHttpSession session = technicianSession();

		// Take the order through the real pick action, then report it.
		pick(session, orderId, lineId, 4);
		reportCompletion(session, orderId);
		Timestamp takenAt = takenAtOf(orderId);
		assertThat(takenAt).isNotNull();

		// Rejected every time, not just on the first attempt.
		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
				.with(csrf())
				.param("quantity", "2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(COMPLETION_REPORTED_ERROR)));

			assertThat(stockOf(partId)).isEqualTo(16);
			assertThat(reservedQuantityOf(lineId)).isEqualTo(6);
			assertThat(pickedQuantityOf(lineId)).isEqualTo(4);
			assertThat(takenAtOf(orderId)).isEqualTo(takenAt);
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

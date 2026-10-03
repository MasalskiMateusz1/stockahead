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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code OrderController.cancelForm}/{@code cancel} (FR-011):
 * cancelling an untaken order hands its reservation to the next order with
 * stock unchanged; cancelling a taken order raises stock by exactly the
 * returned amounts (full, partial and zero returns), records them with the
 * cancel moment and account, and reallocates the returned and freed units to
 * a short order; a line picked twice (first pick over half the reservation)
 * can return everything it picked; an over-return, a malformed or missing
 * return, a pending completion report (until rejected), and an already
 * cancelled or completed order are refused with no change; a cancelled order
 * refuses picks and reports and drops out of {@code /orders},
 * {@code /picking} and {@code /purchasing}; an unknown order is 404, a
 * technician gets 403 and an unauthenticated request is sent to login. Taken
 * and reported states come from the real pick/report endpoints. Also proves
 * the V9 constraints at the PostgreSQL level. Against real Postgres via
 * Testcontainers, deliberately not {@code @Transactional} so the
 * reallocation and constraints commit or fire for real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelIntegrationTests {

	/** Moments render as date + minute, no seconds or zone. */
	private static final String MINUTE_PATTERN = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}";

	private static final String MANAGER_EMAIL = "order-cancel-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-cancel-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String PROJECT_NAME = "Order Cancel Board";

	private static final String OTHER_PROJECT_NAME = "Next In Line Board";

	private static final String CANCEL_LINK = "Anuluj zlecenie";

	private static final String CONFIRM_BUTTON = "Potwierdź anulowanie";

	private static final String NOTHING_PICKED = "Z tego zlecenia nie pobrano żadnych części.";

	private static final String RETURNED_COLUMN = "Zwrócona ilość";

	private static final String CHANGE_FORM = "Zmiana priorytetu i terminu";

	private static final String NOT_CANCELLABLE_ERROR = "Nie można anulować zlecenia, które nie jest otwarte lub czeka na potwierdzenie zakończenia — odrzuć zgłoszenie zakończenia, aby anulować.";

	private static final String RETURN_NOT_INTEGER_ERROR = "Ilość do zwrotu musi być liczbą całkowitą nie mniejszą niż 0.";

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

	private Long otherProjectId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject(PROJECT_NAME);
		otherProjectId = seedProject(OTHER_PROJECT_NAME);
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

	private Long accountIdOf(String email) {
		return accountRepository.findByEmail(email).orElseThrow().getId();
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

	/**
	 * An OPEN order with an explicit priority and {@code created_at}, so
	 * allocation order never ties on a shared {@code now()}.
	 */
	private Long seedOrder(Long orderProjectId, Priority priority, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, orderProjectId, priority.name(), LocalDate.now().plusDays(7), Timestamp.from(createdAt)));
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

	private void report(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(session).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/picking/" + orderId));
	}

	private void confirm(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/confirm-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders"));
	}

	private void reject(MockHttpSession session, Long orderId) throws Exception {
		mockMvc.perform(post("/orders/{id}/reject-completion", orderId).session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders"));
	}

	/**
	 * A cancel POST for {@code orderId}. {@code lineReturns} is a flat list of
	 * {@code lineId, seenPicked, returned} triples, one per line the form
	 * showed.
	 */
	private MockHttpServletRequestBuilder cancelRequest(MockHttpSession session, Long orderId,
			long... lineReturns) {
		MockHttpServletRequestBuilder request = post("/orders/{id}/cancel", orderId).with(csrf());
		if (session != null) {
			request = request.session(session);
		}
		for (int i = 0; i < lineReturns.length; i += 3) {
			request = request.param("seenPicked_" + lineReturns[i], Long.toString(lineReturns[i + 1]))
				.param("returned_" + lineReturns[i], Long.toString(lineReturns[i + 2]));
		}
		return request;
	}

	private void cancel(MockHttpSession session, Long orderId, long... lineReturns) throws Exception {
		mockMvc.perform(cancelRequest(session, orderId, lineReturns))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderId));
	}

	private void cancelRejected(MockHttpSession session, Long orderId, String error, long... lineReturns)
			throws Exception {
		mockMvc.perform(cancelRequest(session, orderId, lineReturns))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(error)));
	}

	private String statusOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private Timestamp cancelledAtOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT cancelled_at FROM orders WHERE id = ?", Timestamp.class,
				orderId);
	}

	private Long cancelledByOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", Long.class, orderId);
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

	/** Asserts the order is still OPEN with no cancel recorded. */
	private void assertNotCancelled(Long orderId) {
		assertThat(statusOf(orderId)).isEqualTo("OPEN");
		assertThat(cancelledAtOf(orderId)).isNull();
		assertThat(cancelledByOf(orderId)).isNull();
	}

	// ---- happy paths --------------------------------------------------

	@Test
	void cancellingUntakenOrderReleasesReservationToNextOrder() throws Exception {
		Instant now = Instant.now();
		Long partId = seedPart("Untaken Resistor", 5);
		Long cancelled = seedOrder(projectId, Priority.NORMAL, now.minusSeconds(60));
		Long cancelledLine = seedOrderLine(cancelled, partId, 5, 5);
		Long next = seedOrder(otherProjectId, Priority.NORMAL, now);
		Long nextLine = seedOrderLine(next, partId, 5, 0);
		MockHttpSession manager = managerSession();

		mockMvc.perform(get("/orders/{id}", cancelled).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(CANCEL_LINK)))
			.andExpect(content().string(containsString("/orders/" + cancelled + "/cancel")));
		mockMvc.perform(get("/orders/{id}/cancel", cancelled).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(NOTHING_PICKED)))
			.andExpect(content().string(containsString(CONFIRM_BUTTON)));

		cancel(manager, cancelled);

		assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
		assertThat(cancelledAtOf(cancelled)).isNotNull();
		assertThat(cancelledByOf(cancelled)).isEqualTo(accountIdOf(MANAGER_EMAIL));
		assertThat(reservedOf(cancelledLine)).isZero();
		assertThat(returnedOf(cancelledLine)).isZero();
		assertThat(reservedOf(nextLine)).isEqualTo(5);
		assertThat(stockOf(partId)).isEqualTo(5);

		mockMvc.perform(get("/orders/{id}", cancelled).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(matchesPattern(
					"(?s).*Anulowane <span>" + MINUTE_PATTERN + "</span>, <span>" + MANAGER_EMAIL + "</span>.*")))
			.andExpect(content().string(containsString(RETURNED_COLUMN)))
			.andExpect(content().string(not(containsString(CANCEL_LINK))))
			.andExpect(content().string(not(containsString(CHANGE_FORM))))
			.andExpect(content().string(not(containsString("Brakująca ilość"))));
	}

	@Test
	void cancellingTakenOrderReturnsEnteredUnitsAndReallocatesThem() throws Exception {
		Instant now = Instant.now();
		Long fullPart = seedPart("Full Return Capacitor", 10);
		Long partialPart = seedPart("Partial Return Diode", 10);
		Long keptPart = seedPart("Kept Inductor", 10);
		Long cancelled = seedOrder(projectId, Priority.HIGH, now.minusSeconds(60));
		Long fullLine = seedOrderLine(cancelled, fullPart, 6, 6);
		Long partialLine = seedOrderLine(cancelled, partialPart, 6, 6);
		Long keptLine = seedOrderLine(cancelled, keptPart, 4, 4);
		Long shortOrder = seedOrder(otherProjectId, Priority.NORMAL, now);
		Long shortFullLine = seedOrderLine(shortOrder, fullPart, 10, 4);
		Long shortPartialLine = seedOrderLine(shortOrder, partialPart, 10, 4);
		Long shortKeptLine = seedOrderLine(shortOrder, keptPart, 10, 6);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, cancelled, fullLine, 6);
		pick(technician, cancelled, partialLine, 4);
		pick(technician, cancelled, keptLine, 3);
		assertThat(stockOf(fullPart)).isEqualTo(4);
		assertThat(stockOf(partialPart)).isEqualTo(6);
		assertThat(stockOf(keptPart)).isEqualTo(7);

		mockMvc.perform(get("/orders/{id}/cancel", cancelled).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("name=\"returned_" + fullLine + "\"")))
			.andExpect(content().string(containsString("name=\"seenPicked_" + partialLine + "\"")))
			.andExpect(content().string(containsString("Full Return Capacitor")))
			.andExpect(content().string(containsString(CONFIRM_BUTTON)));

		cancel(manager, cancelled, fullLine, 6, 6, partialLine, 4, 2, keptLine, 3, 0);

		assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
		assertThat(cancelledAtOf(cancelled)).isNotNull();
		assertThat(cancelledByOf(cancelled)).isEqualTo(accountIdOf(MANAGER_EMAIL));
		assertThat(stockOf(fullPart)).isEqualTo(10);
		assertThat(stockOf(partialPart)).isEqualTo(8);
		assertThat(stockOf(keptPart)).isEqualTo(7);
		assertThat(returnedOf(fullLine)).isEqualTo(6);
		assertThat(returnedOf(partialLine)).isEqualTo(2);
		assertThat(returnedOf(keptLine)).isZero();
		assertThat(pickedOf(fullLine)).isEqualTo(6);
		assertThat(pickedOf(partialLine)).isEqualTo(4);
		assertThat(pickedOf(keptLine)).isEqualTo(3);
		assertThat(reservedOf(fullLine)).isZero();
		assertThat(reservedOf(partialLine)).isZero();
		assertThat(reservedOf(keptLine)).isZero();
		// The short order receives the returned units and the released reservation.
		assertThat(reservedOf(shortFullLine)).isEqualTo(10);
		assertThat(reservedOf(shortPartialLine)).isEqualTo(8);
		assertThat(reservedOf(shortKeptLine)).isEqualTo(7);

		mockMvc.perform(get("/orders/{id}", cancelled).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(RETURNED_COLUMN)));
	}

	@Test
	void lineReturnedInFullAfterTwoPicksRestoresStockExactly() throws Exception {
		Instant now = Instant.now();
		Long partId = seedPart("Twice Picked Relay", 10);
		Long cancelled = seedOrder(projectId, Priority.HIGH, now.minusSeconds(60));
		Long lineId = seedOrderLine(cancelled, partId, 10, 10);
		Long next = seedOrder(otherProjectId, Priority.NORMAL, now);
		Long nextLine = seedOrderLine(next, partId, 5, 0);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, cancelled, lineId, 6);
		pick(technician, cancelled, lineId, 4);
		assertThat(stockOf(partId)).isZero();
		assertThat(reservedOf(lineId)).isZero();
		assertThat(pickedOf(lineId)).isEqualTo(10);

		cancel(manager, cancelled, lineId, 10, 10);

		assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
		assertThat(stockOf(partId)).isEqualTo(10);
		assertThat(returnedOf(lineId)).isEqualTo(10);
		assertThat(reservedOf(nextLine)).isEqualTo(5);
	}

	@Test
	void returnParamsForAnotherOrdersLineAreIgnored() throws Exception {
		Instant now = Instant.now();
		Long cancelledPart = seedPart("Cancelled Order Switch", 10);
		Long otherPart = seedPart("Other Order Switch", 10);
		Long cancelled = seedOrder(projectId, Priority.NORMAL, now.minusSeconds(60));
		Long cancelledLine = seedOrderLine(cancelled, cancelledPart, 5, 5);
		Long other = seedOrder(otherProjectId, Priority.NORMAL, now);
		Long otherLine = seedOrderLine(other, otherPart, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, cancelled, cancelledLine, 2);
		pick(technician, other, otherLine, 3);

		// The form also carries a full return for the other order's picked line.
		cancel(manager, cancelled, cancelledLine, 2, 2, otherLine, 3, 3);

		assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
		assertThat(stockOf(cancelledPart)).isEqualTo(10);
		assertThat(returnedOf(cancelledLine)).isEqualTo(2);
		assertNotCancelled(other);
		assertThat(stockOf(otherPart)).isEqualTo(7);
		assertThat(pickedOf(otherLine)).isEqualTo(3);
		assertThat(returnedOf(otherLine)).isZero();
		assertThat(reservedOf(otherLine)).isEqualTo(2);
	}

	// ---- rejections ---------------------------------------------------

	@Test
	void returnAbovePickedIsRejectedWithNoChange() throws Exception {
		Long partId = seedPart("Over Return Fuse", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 3);

		mockMvc.perform(cancelRequest(manager, orderId, lineId, 3, 4))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nie można zwrócić więcej niż 3 szt.")))
			.andExpect(content().string(containsString("value=\"4\"")));

		assertNotCancelled(orderId);
		assertThat(stockOf(partId)).isEqualTo(7);
		assertThat(reservedOf(lineId)).isEqualTo(2);
		assertThat(returnedOf(lineId)).isZero();
	}

	@Test
	void malformedOrMissingReturnIsRejectedWithNoChange() throws Exception {
		Long partId = seedPart("Malformed Return Switch", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 3);

		for (String raw : new String[] { "abc", "-1", "99999999999" }) {
			mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
				.with(csrf())
				.param("seenPicked_" + lineId, "3")
				.param("returned_" + lineId, raw))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(RETURN_NOT_INTEGER_ERROR)));
		}
		// The form showed the line, but its return field is missing: an error, not 0.
		mockMvc.perform(post("/orders/{id}/cancel", orderId).session(manager)
			.with(csrf())
			.param("seenPicked_" + lineId, "3"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(RETURN_NOT_INTEGER_ERROR)));

		assertNotCancelled(orderId);
		assertThat(stockOf(partId)).isEqualTo(7);
		assertThat(reservedOf(lineId)).isEqualTo(2);
		assertThat(returnedOf(lineId)).isZero();
	}

	@Test
	void pendingReportBlocksCancelUntilRejected() throws Exception {
		Long partId = seedPart("Reported Transistor", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 2);
		report(technician, orderId);

		mockMvc.perform(get("/orders/{id}", orderId).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(CANCEL_LINK))));
		mockMvc.perform(get("/orders/{id}/cancel", orderId).session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderId));
		cancelRejected(manager, orderId, NOT_CANCELLABLE_ERROR, lineId, 2, 2);

		assertNotCancelled(orderId);
		assertThat(stockOf(partId)).isEqualTo(8);
		assertThat(reservedOf(lineId)).isEqualTo(3);

		reject(manager, orderId);
		cancel(manager, orderId, lineId, 2, 2);

		assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
		assertThat(stockOf(partId)).isEqualTo(10);
		assertThat(returnedOf(lineId)).isEqualTo(2);
		assertThat(reservedOf(lineId)).isZero();
	}

	@Test
	void cancelledOrCompletedOrderIsRejected() throws Exception {
		Long partId = seedPart("Closed Order Crystal", 10);
		Long cancelledOrder = seedOrder(projectId, Priority.NORMAL, Instant.now().minusSeconds(60));
		seedOrderLine(cancelledOrder, partId, 3, 3);
		Long completedOrder = seedOrder(otherProjectId, Priority.NORMAL, Instant.now());
		Long completedLine = seedOrderLine(completedOrder, partId, 3, 3);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		cancel(manager, cancelledOrder);
		Timestamp cancelledAt = cancelledAtOf(cancelledOrder);

		mockMvc.perform(get("/orders/{id}/cancel", cancelledOrder).session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + cancelledOrder));
		cancelRejected(manager, cancelledOrder, NOT_CANCELLABLE_ERROR);
		assertThat(statusOf(cancelledOrder)).isEqualTo("CANCELLED");
		assertThat(cancelledAtOf(cancelledOrder)).isEqualTo(cancelledAt);

		pick(technician, completedOrder, completedLine, 2);
		report(technician, completedOrder);
		confirm(manager, completedOrder);
		int stockBefore = stockOf(partId);

		mockMvc.perform(get("/orders/{id}/cancel", completedOrder).session(manager))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + completedOrder));
		cancelRejected(manager, completedOrder, NOT_CANCELLABLE_ERROR, completedLine, 2, 2);

		assertThat(statusOf(completedOrder)).isEqualTo("COMPLETED");
		assertThat(cancelledAtOf(completedOrder)).isNull();
		assertThat(returnedOf(completedLine)).isZero();
		assertThat(stockOf(partId)).isEqualTo(stockBefore);
	}

	@Test
	void cancelledOrderRefusesPickAndReportAndLeavesTheLists() throws Exception {
		Long partId = seedPart("Scarce Oscillator", 2);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 2);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 1);

		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PROJECT_NAME)));
		mockMvc.perform(get("/orders").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PROJECT_NAME)));
		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PROJECT_NAME)));

		cancel(manager, orderId, lineId, 1, 0);
		assertThat(stockOf(partId)).isEqualTo(1);

		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(technician)
			.with(csrf())
			.param("quantity", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));
		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ORDER_NOT_OPEN_ERROR)));

		assertThat(pickedOf(lineId)).isEqualTo(1);
		assertThat(stockOf(partId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT completion_reported_at FROM orders WHERE id = ?",
				Timestamp.class, orderId)).isNull();

		mockMvc.perform(get("/purchasing").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PROJECT_NAME))));
		mockMvc.perform(get("/orders").session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PROJECT_NAME))));
		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(PROJECT_NAME))));
	}

	@Test
	void unknownOrderIs404() throws Exception {
		MockHttpSession manager = managerSession();

		mockMvc.perform(get("/orders/{id}/cancel", 999_999_999L).session(manager))
			.andExpect(status().isNotFound());
		mockMvc.perform(cancelRequest(manager, 999_999_999L))
			.andExpect(status().isNotFound());
	}

	// ---- access -------------------------------------------------------

	@Test
	void technicianIsForbiddenAndNothingChanges() throws Exception {
		Long partId = seedPart("Forbidden Cancel Fuse", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession technician = technicianSession();

		pick(technician, orderId, lineId, 2);

		mockMvc.perform(get("/orders/{id}/cancel", orderId).session(technician))
			.andExpect(status().isForbidden());
		mockMvc.perform(cancelRequest(technician, orderId, lineId, 2, 2))
			.andExpect(status().isForbidden());

		assertNotCancelled(orderId);
		assertThat(stockOf(partId)).isEqualTo(8);
		assertThat(reservedOf(lineId)).isEqualTo(3);
		assertThat(returnedOf(lineId)).isZero();
	}

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		Long partId = seedPart("Anonymous Cancel Fuse", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);

		mockMvc.perform(get("/orders/{id}/cancel", orderId))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
		mockMvc.perform(cancelRequest(null, orderId))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));

		assertNotCancelled(orderId);
		assertThat(reservedOf(lineId)).isEqualTo(5);
	}

	// ---- V9 schema ----------------------------------------------------

	@Test
	void databaseRejectsReturnAbovePicked() {
		Long partId = seedPart("Schema Return Part", 10);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());
		Long lineId = jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity, picked_quantity) "
						+ "VALUES (?, ?, 5, 0, 2) RETURNING id",
				Long.class, orderId, partId);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE order_lines SET returned_quantity = 3 WHERE id = ?",
				lineId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE order_lines SET returned_quantity = -1 WHERE id = ?",
				lineId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(returnedOf(lineId)).isZero();

		jdbcTemplate.update("UPDATE order_lines SET returned_quantity = 2 WHERE id = ?", lineId);
		assertThat(returnedOf(lineId)).isEqualTo(2);
	}

	@Test
	void databaseRejectsInconsistentCancellation() {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		Long managerId = accountIdOf(MANAGER_EMAIL);
		Long orderId = seedOrder(projectId, Priority.NORMAL, Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?",
				orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE orders SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE orders SET cancelled_at = now(), cancelled_by = ? WHERE id = ?", managerId, orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertNotCancelled(orderId);

		jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = ? "
				+ "WHERE id = ?", managerId, orderId);
		assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
		assertThat(cancelledByOf(orderId)).isEqualTo(managerId);
	}

}

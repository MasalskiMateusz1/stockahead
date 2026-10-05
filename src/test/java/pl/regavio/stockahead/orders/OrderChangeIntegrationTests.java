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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code OrderController.change} (FR-021): raising an untaken order's
 * priority takes the unpicked reservation from an older, lower untaken order
 * and lowering it gives it back, an earlier date at equal priority reorders
 * the allocation, a taken order (taken through the real pick endpoint) is
 * refused and shows no form, a past date other than the current one is
 * refused while resubmitting the current past date with a new priority is
 * accepted, an unknown priority is refused with the submitted values kept,
 * an unknown order is 404, a technician gets 403 and an unauthenticated
 * request is sent to login, every refusal leaving the order untouched.
 * Against real Postgres via Testcontainers, deliberately not
 * {@code @Transactional} so the reallocation commits for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class OrderChangeIntegrationTests {

	private static final String MANAGER_EMAIL = "order-change-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-change-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String CHANGE_FORM = "Zmiana priorytetu i terminu";

	private static final String NOT_CHANGEABLE_ERROR = "Nie można zmienić zlecenia, które zostało już podjęte lub nie jest otwarte.";

	private static final String PRIORITY_INVALID_ERROR = "Wybierz poprawny priorytet.";

	private static final String DATE_PAST_ERROR = "Wymagany termin nie może być w przeszłości.";

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
		projectId = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES ('Order Change Board', true) RETURNING id", Long.class));
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
			company = companyFixtures.company("OrderChangeIntegrationTests Co");
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

	private Long seedPart(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	/**
	 * An OPEN order with an explicit priority, required date and
	 * {@code created_at}, so allocation order never ties on a shared
	 * {@code now()}.
	 */
	private Long seedOrder(Priority priority, LocalDate requiredDate, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, projectId, priority.name(), requiredDate, Timestamp.from(createdAt)));
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

	private void change(MockHttpSession session, Long orderId, Priority priority, LocalDate requiredDate)
			throws Exception {
		mockMvc.perform(post("/orders/{id}/change", orderId).session(session)
			.with(csrf())
			.param("priority", priority.name())
			.param("requiredDate", requiredDate.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/orders/" + orderId));
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private String priorityOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT priority FROM orders WHERE id = ?", String.class, orderId);
	}

	private LocalDate requiredDateOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT required_date FROM orders WHERE id = ?", LocalDate.class,
				orderId);
	}

	// ---- reallocation -------------------------------------------------

	@Test
	void raisingPriorityTakesReservationAndLoweringGivesItBack() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Instant now = Instant.now();
		Long partId = seedPart("Change Resistor", 5);
		Long olderNormal = seedOrder(Priority.NORMAL, date, now.minusSeconds(60));
		Long olderLine = seedOrderLine(olderNormal, partId, 5, 5);
		Long newerLow = seedOrder(Priority.LOW, date, now);
		Long newerLine = seedOrderLine(newerLow, partId, 5, 0);
		MockHttpSession manager = managerSession();

		mockMvc.perform(get("/orders/{id}", newerLow).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(CHANGE_FORM)))
			.andExpect(content().string(containsString("value=\"" + date + "\"")));

		change(manager, newerLow, Priority.HIGH, date);

		assertThat(priorityOf(newerLow)).isEqualTo("HIGH");
		assertThat(reservedOf(newerLine)).isEqualTo(5);
		assertThat(reservedOf(olderLine)).isZero();

		change(manager, newerLow, Priority.LOW, date);

		assertThat(priorityOf(newerLow)).isEqualTo("LOW");
		assertThat(reservedOf(newerLine)).isZero();
		assertThat(reservedOf(olderLine)).isEqualTo(5);
	}

	@Test
	void earlierDateAtEqualPriorityReordersAllocation() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Instant now = Instant.now();
		Long partId = seedPart("Date Capacitor", 3);
		Long olderOrder = seedOrder(Priority.NORMAL, date, now.minusSeconds(60));
		Long olderLine = seedOrderLine(olderOrder, partId, 3, 3);
		Long newerOrder = seedOrder(Priority.NORMAL, date, now);
		Long newerLine = seedOrderLine(newerOrder, partId, 3, 0);
		MockHttpSession manager = managerSession();

		LocalDate earlier = LocalDate.now().plusDays(2);
		change(manager, newerOrder, Priority.NORMAL, earlier);

		assertThat(requiredDateOf(newerOrder)).isEqualTo(earlier);
		assertThat(reservedOf(newerLine)).isEqualTo(3);
		assertThat(reservedOf(olderLine)).isZero();
	}

	// ---- rejections ---------------------------------------------------

	@Test
	void takenOrderIsRejectedAndFormIsAbsent() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Long partId = seedPart("Taken Inductor", 10);
		Long orderId = seedOrder(Priority.NORMAL, date, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = technicianSession();
		MockHttpSession manager = loginAs(MANAGER_EMAIL);

		pick(technician, orderId, lineId, 1);
		pick(technician, orderId, lineId, 1);

		mockMvc.perform(get("/orders/{id}", orderId).session(manager))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(CHANGE_FORM))));

		mockMvc.perform(post("/orders/{id}/change", orderId).session(manager)
			.with(csrf())
			.param("priority", "HIGH")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(NOT_CHANGEABLE_ERROR)))
			.andExpect(content().string(not(containsString(CHANGE_FORM))));

		assertThat(priorityOf(orderId)).isEqualTo("NORMAL");
		assertThat(requiredDateOf(orderId)).isEqualTo(date);
		assertThat(reservedOf(lineId)).isEqualTo(3);
	}

	@Test
	void pastDateOtherThanCurrentIsRejected() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Long partId = seedPart("Past Date Diode", 5);
		Long orderId = seedOrder(Priority.NORMAL, date, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession manager = managerSession();

		LocalDate yesterday = LocalDate.now().minusDays(1);
		mockMvc.perform(post("/orders/{id}/change", orderId).session(manager)
			.with(csrf())
			.param("priority", "HIGH")
			.param("requiredDate", yesterday.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DATE_PAST_ERROR)))
			.andExpect(content().string(containsString("value=\"" + yesterday + "\"")));

		assertThat(priorityOf(orderId)).isEqualTo("NORMAL");
		assertThat(requiredDateOf(orderId)).isEqualTo(date);
		assertThat(reservedOf(lineId)).isEqualTo(5);
	}

	@Test
	void currentPastDateIsAcceptedWithNewPriority() throws Exception {
		Long partId = seedPart("Overdue Transistor", 5);
		Long orderId = seedOrder(Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		// Raw SQL kept: no endpoint creates an order with a past date; an order
		// simply becomes overdue as days pass.
		LocalDate overdue = LocalDate.now().minusDays(3);
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate
			.update("UPDATE orders SET required_date = ? WHERE id = ?", overdue, orderId));
		MockHttpSession manager = managerSession();

		change(manager, orderId, Priority.HIGH, overdue);

		assertThat(priorityOf(orderId)).isEqualTo("HIGH");
		assertThat(requiredDateOf(orderId)).isEqualTo(overdue);
		assertThat(reservedOf(lineId)).isEqualTo(5);

		// A different past date is still refused.
		mockMvc.perform(post("/orders/{id}/change", orderId).session(manager)
			.with(csrf())
			.param("priority", "LOW")
			.param("requiredDate", overdue.minusDays(1).toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DATE_PAST_ERROR)));
		assertThat(priorityOf(orderId)).isEqualTo("HIGH");
		assertThat(requiredDateOf(orderId)).isEqualTo(overdue);
	}

	@Test
	void unknownPriorityIsRejected() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Long partId = seedPart("Unknown Priority Relay", 5);
		Long orderId = seedOrder(Priority.LOW, date, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession manager = managerSession();

		LocalDate submitted = LocalDate.now().plusDays(3);
		mockMvc.perform(post("/orders/{id}/change", orderId).session(manager)
			.with(csrf())
			.param("priority", "URGENT")
			.param("requiredDate", submitted.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PRIORITY_INVALID_ERROR)))
			.andExpect(content().string(containsString("value=\"" + submitted + "\"")));

		assertThat(priorityOf(orderId)).isEqualTo("LOW");
		assertThat(requiredDateOf(orderId)).isEqualTo(date);
		assertThat(reservedOf(lineId)).isEqualTo(5);
	}

	@Test
	void unknownOrderIs404() throws Exception {
		MockHttpSession manager = managerSession();

		mockMvc.perform(post("/orders/{id}/change", 999_999_999L).session(manager)
			.with(csrf())
			.param("priority", "HIGH")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isNotFound());
	}

	// ---- access -------------------------------------------------------

	@Test
	void technicianIsForbiddenAndNothingChanges() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Long partId = seedPart("Forbidden Fuse", 5);
		Long orderId = seedOrder(Priority.LOW, date, Instant.now());
		Long lineId = seedOrderLine(orderId, partId, 5, 5);
		MockHttpSession technician = technicianSession();

		mockMvc.perform(post("/orders/{id}/change", orderId).session(technician)
			.with(csrf())
			.param("priority", "HIGH")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isForbidden());

		assertThat(priorityOf(orderId)).isEqualTo("LOW");
		assertThat(requiredDateOf(orderId)).isEqualTo(date);
		assertThat(reservedOf(lineId)).isEqualTo(5);
	}

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		LocalDate date = LocalDate.now().plusDays(7);
		Long orderId = seedOrder(Priority.LOW, date, Instant.now());

		mockMvc.perform(post("/orders/{id}/change", orderId).with(csrf())
			.param("priority", "HIGH")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));

		assertThat(priorityOf(orderId)).isEqualTo("LOW");
	}

}

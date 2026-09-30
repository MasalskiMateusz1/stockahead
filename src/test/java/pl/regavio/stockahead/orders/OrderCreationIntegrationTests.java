package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code OrderController}'s creation flow (Phase 3): validation of
 * quantity/date/project, the BOM-to-{@link OrderLine} snapshot,
 * {@link ReservationAllocator} invocation inside the same transaction, the
 * redirect to the not-yet-built {@code /orders/{id}} detail page (asserted
 * only via the {@code Location} header and DB state, per Phase 4 owning that
 * route), and the role split on both order-creation routes. Against a real
 * Postgres via Testcontainers, deliberately not {@code @Transactional} so the
 * DB constraints genuinely fire, matching
 * {@code ProjectBomIntegrationTests}'s fixture style.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderCreationIntegrationTests {

	private static final String MANAGER_EMAIL = "order-creation-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-creation-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String QUANTITY_NOT_INTEGER_ERROR = "Liczba sztuk musi być liczbą całkowitą.";

	private static final String QUANTITY_NOT_POSITIVE_ERROR = "Liczba sztuk musi wynosić co najmniej 1.";

	private static final String PROJECT_UNAVAILABLE_ERROR = "Projekt nieaktywny lub nie istnieje.";

	private static final String DATE_REQUIRED_ERROR = "Wymagany termin jest wymagany i musi być poprawną datą.";

	private static final String DATE_PAST_ERROR = "Wymagany termin nie może być w przeszłości.";

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

	private Long resistorId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject("Resistor Board", true);
		resistorId = seedPart("Resistor 10k", 6);
		seedBomLine(projectId, resistorId, 10);
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

	private void deactivateProject(Long id) {
		transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE projects SET active = false WHERE id = ?", id));
	}

	private int orderCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class);
	}

	private Long soleOrderId() {
		return jdbcTemplate.queryForObject("SELECT id FROM orders ORDER BY id", Long.class);
	}

	private int reservedQuantityFor(Long orderId, Long partId) {
		return jdbcTemplate.queryForObject(
				"SELECT reserved_quantity FROM order_lines WHERE order_id = ? AND part_id = ?", Integer.class,
				orderId, partId);
	}

	private int requiredQuantityFor(Long orderId, Long partId) {
		return jdbcTemplate.queryForObject(
				"SELECT required_quantity FROM order_lines WHERE order_id = ? AND part_id = ?", Integer.class,
				orderId, partId);
	}

	// ---- happy path -----------------------------------------------------

	@Test
	void managerCreatesOrderReservingAvailableStockAndRedirectsToDetail() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/orders").session(session).with(csrf())
			.param("projectId", projectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "HIGH")
			.param("requiredDate", LocalDate.now().plusDays(7).toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", matchesPattern("/orders/\\d+")));

		assertThat(orderCount()).isEqualTo(1);
		Long orderId = soleOrderId();
		assertThat(requiredQuantityFor(orderId, resistorId)).isEqualTo(10);
		assertThat(reservedQuantityFor(orderId, resistorId)).isEqualTo(6);
	}

	// ---- validation -------------------------------------------------------

	@Test
	void invalidQuantityUnitsIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		for (String[] attempt : new String[][] { { "0", QUANTITY_NOT_POSITIVE_ERROR },
				{ "-1", QUANTITY_NOT_POSITIVE_ERROR }, { "abc", QUANTITY_NOT_INTEGER_ERROR } }) {
			mockMvc.perform(post("/orders").session(session).with(csrf())
				.param("projectId", projectId.toString())
				.param("quantityUnits", attempt[0])
				.param("priority", "NORMAL")
				.param("requiredDate", LocalDate.now().plusDays(1).toString()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(attempt[1])));
		}

		assertThat(orderCount()).isZero();
	}

	@Test
	void missingRequiredDateIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/orders").session(session).with(csrf())
			.param("projectId", projectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "NORMAL")
			.param("requiredDate", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DATE_REQUIRED_ERROR)));

		assertThat(orderCount()).isZero();
	}

	@Test
	void pastRequiredDateIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/orders").session(session).with(csrf())
			.param("projectId", projectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "NORMAL")
			.param("requiredDate", LocalDate.now().minusDays(1).toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(DATE_PAST_ERROR)));

		assertThat(orderCount()).isZero();
	}

	@Test
	void inactiveProjectIsRejectedWithErrorAndNoRow() throws Exception {
		deactivateProject(projectId);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/orders").session(session).with(csrf())
			.param("projectId", projectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "NORMAL")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(PROJECT_UNAVAILABLE_ERROR)));

		assertThat(orderCount()).isZero();
	}

	@Test
	void nonexistentProjectIsRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		for (String projectIdValue : List.of("999999", "not-a-number", "")) {
			mockMvc.perform(post("/orders").session(session).with(csrf())
				.param("projectId", projectIdValue)
				.param("quantityUnits", "1")
				.param("priority", "NORMAL")
				.param("requiredDate", LocalDate.now().plusDays(1).toString()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(PROJECT_UNAVAILABLE_ERROR)));
		}

		assertThat(orderCount()).isZero();
	}

	// ---- concurrency -----------------------------------------------------

	/**
	 * The HTTP-level counterpart to
	 * {@code ReservationConcurrencyTests#twoConcurrentOrdersForScarcePartNeverTogetherReserveMoreThanStock()},
	 * which calls {@link ReservationAllocator#reallocateAll()} directly and so
	 * bypasses {@code OrderController}'s own transaction boundary. Two
	 * concurrent {@code POST /orders} requests each ask for all of a scarce
	 * part's stock; only one may end up with it reserved. A single manager
	 * session is shared between both threads — {@code MockHttpSession} isn't
	 * request-exclusive, and {@code managerSession()} can't be called twice
	 * here since it re-seeds the same account email each time.
	 */
	@Test
	void twoConcurrentOrderCreationRequestsForScarcePartNeverTogetherReserveMoreThanStock() throws Exception {
		Long scarceProjectId = seedProject("Scarce Board", true);
		Long scarcePartId = seedPart("Scarce Capacitor", 5);
		seedBomLine(scarceProjectId, scarcePartId, 5);

		MockHttpSession session = managerSession();

		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Callable<Void> createCompetingOrder = () -> {
			bothReady.countDown();
			bothReady.await(5, TimeUnit.SECONDS);
			mockMvc.perform(post("/orders").session(session).with(csrf())
				.param("projectId", scarceProjectId.toString())
				.param("quantityUnits", "1")
				.param("priority", "NORMAL")
				.param("requiredDate", LocalDate.now().plusDays(1).toString()))
				.andExpect(status().is3xxRedirection());
			return null;
		};

		try {
			Future<Void> first = executor.submit(createCompetingOrder);
			Future<Void> second = executor.submit(createCompetingOrder);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		Integer totalReserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				scarcePartId);
		Integer stock = jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class,
				scarcePartId);

		assertThat(orderCount()).isEqualTo(2);
		assertThat(totalReserved).isLessThanOrEqualTo(5);
		assertThat(stock).isEqualTo(5);
	}

	// ---- role gating ----------------------------------------------------

	@Test
	void technicianGets403OnOrderCreationRoutes() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/orders/new").session(session))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/orders").session(session).with(csrf())
			.param("projectId", projectId.toString())
			.param("quantityUnits", "1")
			.param("priority", "NORMAL")
			.param("requiredDate", LocalDate.now().plusDays(1).toString()))
			.andExpect(status().isForbidden());

		assertThat(orderCount()).isZero();
	}

}

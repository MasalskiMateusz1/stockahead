package pl.regavio.stockahead.orders;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * Covers {@code OrderController}'s Phase 4 routes: {@code GET /orders} (the
 * open-order list, sorted by {@link ReservationAllocator#ALLOCATION_ORDER})
 * and {@code GET /orders/{id}} (the per-order line detail served by
 * {@link OrderDetailModel}). Orders and their lines are seeded directly via
 * {@code JdbcTemplate} rather than through {@code POST /orders}, since
 * {@link ReservationAllocator}'s allocation math is already covered by
 * {@code OrderCreationIntegrationTests} (Phase 3) — this class only needs a
 * known {@code required}/{@code reserved} pair on the DB row to exercise the
 * detail page's rendering. Against a real Postgres via Testcontainers,
 * deliberately not {@code @Transactional} so the DB constraints genuinely
 * fire, matching {@code OrderCreationIntegrationTests}'s fixture style.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderListAndDetailIntegrationTests {

	private static final String MANAGER_EMAIL = "order-list-detail-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "order-list-detail-technician@example.com";

	private static final String PASSWORD = "correct-password";

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

	private Long seedOrder(Long projectId, int quantityUnits, String priority, LocalDate requiredDate) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"""
				INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at)
				VALUES (?, ?, ?, ?, now())
				RETURNING id
				""",
				Long.class, projectId, quantityUnits, priority, requiredDate));
	}

	private void seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"""
				INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity)
				VALUES (?, ?, ?, ?)
				""",
				orderId, partId, requiredQuantity, reservedQuantity));
	}

	// ---- US-01 acceptance example, through GET /orders/{id} -----------

	@Test
	void orderDetailShowsReservedAndMissingQuantitiesForTheAcceptanceExample() throws Exception {
		Long projectId = seedProject("Resistor Board", true);
		Long resistorId = seedPart("Resistor 10k", 6);
		Long orderId = seedOrder(projectId, 1, "HIGH", LocalDate.now().plusDays(7));
		seedOrderLine(orderId, resistorId, 10, 6);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/orders/" + orderId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Resistor Board")))
			.andExpect(content().string(containsString("Resistor 10k")))
			.andExpect(content().string(containsString("<td>10</td>")))
			.andExpect(content().string(containsString("<td>6</td>")))
			.andExpect(content().string(containsString("<td>4</td>")));
	}

	// ---- list smoke test ------------------------------------------------

	@Test
	void managerSeesSeededOrderOnTheList() throws Exception {
		Long projectId = seedProject("Sensor Board", true);
		Long sensorId = seedPart("Sensor A", 3);
		Long orderId = seedOrder(projectId, 2, "NORMAL", LocalDate.now().plusDays(3));
		seedOrderLine(orderId, sensorId, 5, 3);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/orders").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Sensor Board")));
	}

	// ---- not found --------------------------------------------------------

	@Test
	void unknownOrderIdIs404() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(get("/orders/999999").session(session)).andExpect(status().isNotFound());
	}

	// ---- role gating ----------------------------------------------------

	@Test
	void technicianGets403OnOrderListAndDetailRoutes() throws Exception {
		Long projectId = seedProject("Gated Board", true);
		Long partId = seedPart("Gated Part", 1);
		Long orderId = seedOrder(projectId, 1, "LOW", LocalDate.now().plusDays(1));
		seedOrderLine(orderId, partId, 1, 1);

		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/orders").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(get("/orders/" + orderId).session(session)).andExpect(status().isForbidden());
	}

}

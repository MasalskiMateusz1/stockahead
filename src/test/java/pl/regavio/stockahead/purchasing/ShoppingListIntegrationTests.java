package pl.regavio.stockahead.purchasing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@link ShoppingListModel}'s aggregation and {@link ShoppingListController}'s
 * two routes: {@code GET /purchasing} (HTML, one row per part) and
 * {@code GET /purchasing/export} (CSV, one row per part/blocking-order pair,
 * flattened by {@link ShoppingListCsvWriter}). Orders and their lines are
 * seeded directly via {@code JdbcTemplate} rather than through
 * {@code POST /orders}, matching {@code OrderListAndDetailIntegrationTests}'s
 * fixture and session conventions against a real Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ShoppingListIntegrationTests {

	private static final String MANAGER_EMAIL = "shopping-list-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "shopping-list-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final String CSV_HEADER = "Część;Zlecenie;Termin;Brakująca ilość";

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
		return seedPart(name, quantity, true);
	}

	private Long seedPart(String name, int quantity, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, ?) RETURNING id", Long.class, name,
				quantity, active));
	}

	private Long seedOrder(Long projectId, int quantityUnits, String priority, LocalDate requiredDate) {
		return seedOrder(projectId, quantityUnits, priority, requiredDate, "OPEN");
	}

	private Long seedOrder(Long projectId, int quantityUnits, String priority, LocalDate requiredDate,
			String status) {
		return transactionTemplate.execute(txStatus -> jdbcTemplate.queryForObject(
				"""
				INSERT INTO orders (project_id, quantity_units, priority, required_date, status, created_at)
				VALUES (?, ?, ?, ?, ?, now())
				RETURNING id
				""",
				Long.class, projectId, quantityUnits, priority, requiredDate, status));
	}

	private void seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"""
				INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity)
				VALUES (?, ?, ?, ?)
				""",
				orderId, partId, requiredQuantity, reservedQuantity));
	}

	// ---- /purchasing (HTML) aggregation ---------------------------------

	@Test
	void twoOpenOrdersShortingTheSamePartAreSummedAndBothListedAsBlocking() throws Exception {
		Long partId = seedPart("Capacitor 100nF", 5);
		Long projectAlphaId = seedProject("Alpha Line", true);
		Long projectBetaId = seedProject("Beta Line", true);
		LocalDate alphaDate = LocalDate.now().plusDays(5);
		LocalDate betaDate = LocalDate.now().plusDays(10);
		Long alphaOrderId = seedOrder(projectAlphaId, 1, "NORMAL", alphaDate);
		seedOrderLine(alphaOrderId, partId, 10, 7);
		Long betaOrderId = seedOrder(projectBetaId, 1, "NORMAL", betaDate);
		seedOrderLine(betaOrderId, partId, 8, 4);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Capacitor 100nF")))
			.andExpect(content().string(containsString("<td>7</td>")))
			.andExpect(content().string(containsString("Alpha Line (" + alphaDate + ", 3)")))
			.andExpect(content().string(containsString("Beta Line (" + betaDate + ", 4)")));
	}

	@Test
	void fullyReservedLineContributesNoRow() throws Exception {
		Long partId = seedPart("Fully Reserved Widget", 10);
		Long projectId = seedProject("Satisfied Line", true);
		Long orderId = seedOrder(projectId, 1, "NORMAL", LocalDate.now().plusDays(3));
		seedOrderLine(orderId, partId, 5, 5);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Brak braków.")))
			.andExpect(content().string(not(containsString("Fully Reserved Widget"))));
	}

	@Test
	void cancelledAndCompletedOrdersAreExcludedFromShortages() throws Exception {
		Long cancelledPartId = seedPart("Cancelled Order Part", 2);
		Long cancelledProjectId = seedProject("Cancelled Line", true);
		Long cancelledOrderId = seedOrder(cancelledProjectId, 1, "NORMAL", LocalDate.now().plusDays(3),
				"CANCELLED");
		seedOrderLine(cancelledOrderId, cancelledPartId, 5, 1);

		Long completedPartId = seedPart("Completed Order Part", 2);
		Long completedProjectId = seedProject("Completed Line", true);
		Long completedOrderId = seedOrder(completedProjectId, 1, "NORMAL", LocalDate.now().plusDays(3),
				"COMPLETED");
		seedOrderLine(completedOrderId, completedPartId, 5, 1);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Brak braków.")))
			.andExpect(content().string(not(containsString("Cancelled Order Part"))))
			.andExpect(content().string(not(containsString("Completed Order Part"))));
	}

	@Test
	void shortageAgainstDeactivatedPartIsFlaggedInactive() throws Exception {
		Long partId = seedPart("Retired Connector", 0, false);
		Long projectId = seedProject("Retired Line", true);
		Long orderId = seedOrder(projectId, 1, "NORMAL", LocalDate.now().plusDays(3));
		seedOrderLine(orderId, partId, 5, 2);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Retired Connector")))
			.andExpect(content().string(containsString("(nieaktywna)")));
	}

	@Test
	void noShortagesShowsEmptyStateAndHeaderOnlyCsv() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Brak braków.")));

		MvcResult exportResult = mockMvc.perform(get("/purchasing/export").session(session))
			.andExpect(status().isOk())
			.andReturn();
		byte[] body = exportResult.getResponse().getContentAsByteArray();

		assertThat(body).startsWith(UTF8_BOM);
		String decoded = new String(body, UTF8_BOM.length, body.length - UTF8_BOM.length, StandardCharsets.UTF_8);
		assertThat(decoded).isEqualTo(CSV_HEADER);
	}

	// ---- /purchasing/export (CSV) contract ------------------------------

	@Test
	void exportProducesRfc4180CsvWithBomAndOneRowPerBlockingOrder() throws Exception {
		Long widgetPartId = seedPart("Widget", 10);
		Long widgetProjectId = seedProject("Core Project", true);
		LocalDate widgetDate = LocalDate.now().plusDays(4);
		Long widgetOrderId = seedOrder(widgetProjectId, 1, "NORMAL", widgetDate);
		seedOrderLine(widgetOrderId, widgetPartId, 8, 3);

		Long specialPartId = seedPart("Part; Special", 4);
		Long specialProjectId = seedProject("Special Project", true);
		LocalDate specialDate = LocalDate.now().plusDays(6);
		Long specialOrderId = seedOrder(specialProjectId, 1, "NORMAL", specialDate);
		seedOrderLine(specialOrderId, specialPartId, 6, 1);

		MockHttpSession session = managerSession();

		MvcResult exportResult = mockMvc.perform(get("/purchasing/export").session(session))
			.andExpect(status().isOk())
			.andReturn();

		String contentType = exportResult.getResponse().getContentType();
		assertThat(contentType).startsWith("text/csv");
		String contentDisposition = exportResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
		assertThat(contentDisposition).contains("lista-zakupow.csv");

		byte[] body = exportResult.getResponse().getContentAsByteArray();
		assertThat(body).startsWith(UTF8_BOM);
		String decoded = new String(body, UTF8_BOM.length, body.length - UTF8_BOM.length, StandardCharsets.UTF_8);

		assertThat(decoded).startsWith(CSV_HEADER);
		assertThat(decoded)
			.contains("Widget;Core Project (#" + widgetOrderId + ");" + widgetDate + ";5");
		assertThat(decoded)
			.contains("\"Part; Special\";Special Project (#" + specialOrderId + ");" + specialDate + ";5");
	}

	// ---- role gating ----------------------------------------------------

	@Test
	void technicianGets403OnPurchasingListAndExportRoutes() throws Exception {
		MockHttpSession session = technicianSession();

		mockMvc.perform(get("/purchasing").session(session)).andExpect(status().isForbidden());
		mockMvc.perform(get("/purchasing/export").session(session)).andExpect(status().isForbidden());
	}

}

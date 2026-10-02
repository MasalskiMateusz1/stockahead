package pl.regavio.stockahead.parts;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers the read-only side of {@code StockCorrectionController}: the
 * manager-only correction page with stock, reserved and available units,
 * both forms and the past corrections newest first; the technician's 403;
 * the 404 for an unknown part; the "Koryguj stan" link on {@code /parts}
 * for managers only; and the {@code stock_corrections} CHECK constraints.
 * Against a real Postgres via Testcontainers, deliberately not
 * {@code @Transactional}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockCorrectionIntegrationTests {

	private static final String TECHNICIAN_EMAIL = "correction-technician@example.com";

	private static final String MANAGER_EMAIL = "correction-manager@example.com";

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

	private Long resistorId;

	private Long inactiveId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		resistorId = seedPart("Rezystor 10k", 40, true, "Regał A1");
		inactiveId = seedPart("Stary układ", 3, false, "Regał Z9");
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM stock_corrections");
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

	private Long seedAccount(String email, Role role) {
		return transactionTemplate.execute(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode("correct-password"));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			return accountRepository.save(account).getId();
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password("correct-password"))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedPart(String name, int quantity, boolean active, String location) {
		return transactionTemplate.execute(status -> {
			Long id = jdbcTemplate.queryForObject(
					"INSERT INTO parts (name, quantity, active) VALUES (?, ?, ?) RETURNING id", Long.class, name,
					quantity, active);
			jdbcTemplate.update("INSERT INTO part_locations (part_id, location) VALUES (?, ?)", id, location);
			return id;
		});
	}

	private Long seedProject(String name) {
		return jdbcTemplate.queryForObject("INSERT INTO projects (name, active) VALUES (?, true) RETURNING id",
				Long.class, name);
	}

	private Long seedOpenOrder(Long projectId) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, projectId, Priority.NORMAL.name(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()));
	}

	private Long seedCancelledOrder(Long projectId, Long cancelledBy) {
		Long id = seedOpenOrder(projectId);
		jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = ? "
				+ "WHERE id = ?", cancelledBy, id);
		return id;
	}

	private void seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity,
			int pickedQuantity) {
		jdbcTemplate.update(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity, picked_quantity) "
						+ "VALUES (?, ?, ?, ?, ?)",
				orderId, partId, requiredQuantity, reservedQuantity, pickedQuantity);
	}

	private void seedCorrection(Long partId, Long accountId, int before, int after, String reason,
			Instant createdAt) {
		jdbcTemplate.update(
				"INSERT INTO stock_corrections (part_id, account_id, quantity_before, quantity_after, reason, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				partId, accountId, before, after, reason, Timestamp.from(createdAt));
	}

	private String correctionPage(MockHttpSession session, Long partId) throws Exception {
		return mockMvc.perform(get("/parts/{id}/correction", partId).session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("parts-correction"))
			.andReturn().getResponse().getContentAsString();
	}

	// ---- GET ----------------------------------------------------------

	@Test
	void managerSeesStockReservedAvailableAndBothForms() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		Long projectId = seedProject("Sterownik");
		Long openOrder = seedOpenOrder(projectId);
		jdbcTemplate.update("UPDATE orders SET taken_at = now() WHERE id = ?", openOrder);
		// Picked units already left reservedQuantity: reserved is 12 + 5, never minus picks.
		seedOrderLine(openOrder, resistorId, 20, 12, 8);
		Long otherOpenOrder = seedOpenOrder(projectId);
		seedOrderLine(otherOpenOrder, resistorId, 5, 5, 0);
		Long cancelledOrder = seedCancelledOrder(projectId, managerId);
		seedOrderLine(cancelledOrder, resistorId, 9, 9, 0);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = correctionPage(session, resistorId);

		assertThat(html).contains("Rezystor 10k");
		assertThat(html).contains("Stan: <strong>40</strong>");
		assertThat(html).contains("zarezerwowane: <strong>17</strong>");
		assertThat(html).contains("dostępne: <strong>23</strong>");
		assertThat(html).contains("action=\"/parts/" + resistorId + "/correction/set\"");
		assertThat(html).contains("action=\"/parts/" + resistorId + "/correction/adjust\"");
		assertThat(html).contains("name=\"expectedQuantity\" value=\"40\"");
		assertThat(html).contains("name=\"newQuantity\"");
		assertThat(html).contains("name=\"delta\"");
		assertThat(html).contains("name=\"reason\"");
		assertThat(html).contains("Brak korekt");
		assertThat(html).contains("href=\"/parts\"");
		assertThat(html).contains("td, th");
	}

	@Test
	void managerCanOpenInactivePart() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = correctionPage(session, inactiveId);

		assertThat(html).contains("Stary układ");
		assertThat(html).contains("(nieaktywna)");
		assertThat(html).contains("Stan: <strong>3</strong>");
	}

	@Test
	void pageListsCorrectionsNewestFirstWithAuthorEmail() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		Instant now = Instant.now();
		seedCorrection(resistorId, managerId, 50, 45, "Spis kwartalny", now.minusSeconds(7200));
		seedCorrection(resistorId, managerId, 45, 40, "Uszkodzone przy montażu", now.minusSeconds(60));
		seedCorrection(inactiveId, managerId, 1, 3, "Inna część", now);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = correctionPage(session, resistorId);

		assertThat(html).doesNotContain("Brak korekt");
		assertThat(html).doesNotContain("Inna część");
		assertThat(html).contains(MANAGER_EMAIL);
		assertThat(html).contains("45 → 40", "50 → 45");
		assertThat(html).contains("<td>-5</td>");
		int newer = html.indexOf("Uszkodzone przy montażu");
		int older = html.indexOf("Spis kwartalny");
		assertThat(newer).isPositive();
		assertThat(older).isGreaterThan(newer);
	}

	@Test
	void technicianGets403() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts/{id}/correction", resistorId).session(session))
			.andExpect(status().isForbidden());
	}

	@Test
	void unknownPartGets404() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts/{id}/correction", Long.MAX_VALUE).session(session))
			.andExpect(status().isNotFound());
	}

	// ---- /parts link --------------------------------------------------

	@Test
	void partsListShowsCorrectionLinkToManagerOnly() throws Exception {
		String link = "href=\"/parts/" + resistorId + "/correction\"";

		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		String managerHtml = mockMvc.perform(get("/parts").session(loginAs(MANAGER_EMAIL)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(managerHtml).contains(link);
		assertThat(managerHtml).contains("Koryguj stan");

		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		String technicianHtml = mockMvc.perform(get("/parts").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(technicianHtml).doesNotContain(link);
		assertThat(technicianHtml).doesNotContain("Koryguj stan");
	}

	// ---- schema -------------------------------------------------------

	@Test
	void migrationRejectsBlankReason() {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);

		assertThatThrownBy(() -> seedCorrection(resistorId, managerId, 40, 30, "   ", Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> seedCorrection(resistorId, managerId, 40, 30, "", Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void migrationRejectsEqualBeforeAndAfter() {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);

		assertThatThrownBy(() -> seedCorrection(resistorId, managerId, 40, 40, "Bez zmian", Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void migrationRejectsNegativeQuantities() {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);

		assertThatThrownBy(() -> seedCorrection(resistorId, managerId, 40, -1, "Ujemny", Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> seedCorrection(resistorId, managerId, -1, 40, "Ujemny", Instant.now()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_corrections", Integer.class)).isZero();
	}

}

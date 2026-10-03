package pl.regavio.stockahead.parts;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers {@code StockCorrectionController}: the manager-only correction page
 * with stock, reserved and available units, both forms and the past
 * corrections newest first; the technician's 403 on every route; the 404 for
 * an unknown part; the "Koryguj stan" link on {@code /parts} for managers
 * only; the {@code stock_corrections} CHECK constraints; then both write
 * paths: stock set or adjusted with one correction row written, reservations
 * recomputed (shortages, top-ups, the taken-order deficit), the stale-total
 * check, and every rejection re-rendering with nothing written. Against a real Postgres via Testcontainers, deliberately not
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
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_correction_conflict_trigger ON stock_corrections");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_correction_conflict()");
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

	private Long seedLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity);
	}

	private int stockOf(Long partId) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}

	private int reservedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private int pickedOf(Long lineId) {
		return jdbcTemplate.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineId);
	}

	private List<Map<String, Object>> correctionsOf(Long partId) {
		return jdbcTemplate.queryForList("SELECT account_id, quantity_before, quantity_after, reason "
				+ "FROM stock_corrections WHERE part_id = ? ORDER BY id", partId);
	}

	private String snapshot() {
		List<String> parts = jdbcTemplate.queryForList("SELECT id || ':' || quantity FROM parts ORDER BY id",
				String.class);
		List<String> lines = jdbcTemplate.queryForList(
				"SELECT id || ':' || reserved_quantity || '/' || picked_quantity FROM order_lines ORDER BY id",
				String.class);
		Integer corrections = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_corrections", Integer.class);
		return parts + " / " + lines + " / " + corrections;
	}

	private MockHttpServletRequestBuilder setTotal(MockHttpSession session, Long partId, String newQuantity,
			String expectedQuantity, String reason) {
		return post("/parts/{id}/correction/set", partId).session(session)
			.with(csrf())
			.param("newQuantity", newQuantity)
			.param("expectedQuantity", expectedQuantity)
			.param("reason", reason);
	}

	private MockHttpServletRequestBuilder adjustBy(MockHttpSession session, Long partId, String delta,
			String reason) {
		return post("/parts/{id}/correction/adjust", partId).session(session)
			.with(csrf())
			.param("delta", delta)
			.param("reason", reason);
	}

	/** Performs a correction that must succeed and asserts the redirect and its flash. */
	private MvcResult assertApplied(MockHttpServletRequestBuilder request, String flashMessage) throws Exception {
		return mockMvc.perform(request)
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"))
			.andExpect(flash().attribute("stockCorrected", flashMessage))
			.andReturn();
	}

	/**
	 * Performs a correction that must be rejected: the page re-renders with
	 * the error and the database is unchanged.
	 */
	private String assertRejected(MockHttpServletRequestBuilder request, String expectedError) throws Exception {
		String before = snapshot();
		String html = mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name("parts-correction"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains(expectedError);
		assertThat(snapshot()).isEqualTo(before);
		return html;
	}

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
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

	/** Only the newest {@code HISTORY_LIMIT} corrections are listed; the oldest one drops off. */
	@Test
	void pageListsAtMostTheNewestFiftyCorrections() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		Instant start = Instant.now().minusSeconds(3600);
		for (int i = 0; i <= StockCorrectionController.HISTORY_LIMIT; i++) {
			seedCorrection(resistorId, managerId, 100 + i, 101 + i, "Korekta nr " + i + ".", start.plusSeconds(i));
		}
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = correctionPage(session, resistorId);

		assertThat(html).contains("Korekta nr " + StockCorrectionController.HISTORY_LIMIT + ".", "Korekta nr 1.");
		assertThat(html).doesNotContain("Korekta nr 0.");
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

	// ---- write paths --------------------------------------------------

	@Test
	void setDownThenUpUpdatesStockWritesOneRowEachAndRedirectsWithFlash() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		MvcResult result = assertApplied(setTotal(session, resistorId, " 30 ", "40", "  Spis kwartalny  "),
				"Skorygowano stan części „Rezystor 10k”: 40 → 30.");
		assertThat(stockOf(resistorId)).isEqualTo(30);
		mockMvc.perform(get("/parts").session(session).flashAttrs(result.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Skorygowano stan części „Rezystor 10k”: 40 → 30.")));

		assertApplied(setTotal(session, resistorId, "45", "30", "Znaleziono w szufladzie"),
				"Skorygowano stan części „Rezystor 10k”: 30 → 45.");
		assertThat(stockOf(resistorId)).isEqualTo(45);

		List<Map<String, Object>> rows = correctionsOf(resistorId);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("account_id", managerId)
			.containsEntry("quantity_before", 40)
			.containsEntry("quantity_after", 30)
			.containsEntry("reason", "Spis kwartalny");
		assertThat(rows.get(1)).containsEntry("account_id", managerId)
			.containsEntry("quantity_before", 30)
			.containsEntry("quantity_after", 45)
			.containsEntry("reason", "Znaleziono w szufladzie");

		String html = correctionPage(session, resistorId);
		assertThat(html).contains("30 → 45", "40 → 30", MANAGER_EMAIL);
	}

	@Test
	void adjustUpThenDownUpdatesStockAndWritesOneRowEach() throws Exception {
		Long managerId = seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertApplied(adjustBy(session, resistorId, "+5", " Zwrot z produkcji "),
				"Skorygowano stan części „Rezystor 10k”: 40 → 45.");
		assertThat(stockOf(resistorId)).isEqualTo(45);

		assertApplied(adjustBy(session, resistorId, "-12", "Uszkodzone"),
				"Skorygowano stan części „Rezystor 10k”: 45 → 33.");
		assertThat(stockOf(resistorId)).isEqualTo(33);

		List<Map<String, Object>> rows = correctionsOf(resistorId);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("account_id", managerId)
			.containsEntry("quantity_before", 40)
			.containsEntry("quantity_after", 45)
			.containsEntry("reason", "Zwrot z produkcji");
		assertThat(rows.get(1)).containsEntry("account_id", managerId)
			.containsEntry("quantity_before", 45)
			.containsEntry("quantity_after", 33)
			.containsEntry("reason", "Uszkodzone");
	}

	/** Stock 10 fully reserved by an order needing 10; counted 6 → reserved 6, shortage 4 on /purchasing. */
	@Test
	void downwardSetCoversAShortageOnPurchasing() throws Exception {
		Long partId = seedPart("Mikrokontroler", 10, true, "Regał M1");
		Long orderId = seedOpenOrder(seedProject("Sterownik"));
		Long lineId = seedLine(orderId, partId, 10, 10);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Mikrokontroler"))));

		assertApplied(setTotal(session, partId, "6", "10", "Spis"),
				"Skorygowano stan części „Mikrokontroler”: 10 → 6.");

		assertThat(stockOf(partId)).isEqualTo(6);
		assertThat(reservedOf(lineId)).isEqualTo(6);
		String purchasing = mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> purchasingRow = rowCells(purchasing, "Mikrokontroler");
		assertThat(purchasingRow.get(1)).isEqualTo("4");
		assertThat(purchasingRow.get(2)).contains("Sterownik");
	}

	/** A short taken order gains units from an upward correction, as with a delivery. */
	@Test
	void upwardCorrectionTopsUpAShortTakenOrder() throws Exception {
		Long partId = seedPart("Tranzystor", 5, true, "Regał T1");
		Long orderId = seedOpenOrder(seedProject("Zasilacz"));
		Long lineId = seedLine(orderId, partId, 10, 5);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		pick(session, orderId, lineId, 2);
		assertThat(stockOf(partId)).isEqualTo(3);
		assertThat(reservedOf(lineId)).isEqualTo(3);

		assertApplied(adjustBy(session, partId, "4", "Znaleziono na regale"),
				"Skorygowano stan części „Tranzystor”: 3 → 7.");

		assertThat(stockOf(partId)).isEqualTo(7);
		// Unmet = 10 required - 2 picked = 8; all 7 units in stock go to the order.
		assertThat(reservedOf(lineId)).isEqualTo(7);
		assertThat(pickedOf(lineId)).isEqualTo(2);
	}

	/**
	 * A taken order holds 8 unpicked; the count finds 5. The reservation
	 * shrinks to 5, the shortage shows on /purchasing, and a later pick of 6
	 * is rejected.
	 */
	@Test
	void takenDeficitShrinksTheReservationAndBlocksALargerPick() throws Exception {
		Long partId = seedPart("Przekaźnik", 10, true, "Regał P1");
		Long orderId = seedOpenOrder(seedProject("Automat"));
		Long lineId = seedLine(orderId, partId, 10, 10);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);
		pick(session, orderId, lineId, 2);
		assertThat(stockOf(partId)).isEqualTo(8);
		assertThat(reservedOf(lineId)).isEqualTo(8);

		assertApplied(setTotal(session, partId, "5", "8", "Spis"),
				"Skorygowano stan części „Przekaźnik”: 8 → 5.");

		assertThat(stockOf(partId)).isEqualTo(5);
		assertThat(reservedOf(lineId)).isEqualTo(5);
		assertThat(pickedOf(lineId)).isEqualTo(2);
		String purchasing = mockMvc.perform(get("/purchasing").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> purchasingRow = rowCells(purchasing, "Przekaźnik");
		assertThat(purchasingRow.get(1)).isEqualTo("3");
		assertThat(purchasingRow.get(2)).contains("Automat");
		String orderDetail = mockMvc.perform(get("/orders/{id}", orderId).session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		// Część, wymagana, zarezerwowana, pobrana, brakująca.
		assertThat(rowCells(orderDetail, "Przekaźnik")).containsExactly("Przekaźnik", "10", "5", "2", "3");

		String pickHtml = mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId)
			.session(session)
			.with(csrf())
			.param("quantity", "6"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(pickHtml).contains("Nie można pobrać więcej niż 5 szt.");
		assertThat(stockOf(partId)).isEqualTo(5);
		assertThat(reservedOf(lineId)).isEqualTo(5);
		assertThat(pickedOf(lineId)).isEqualTo(2);
	}

	@Test
	void staleTotalIsRejectedAndThePageOffersTheFreshStock() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = assertRejected(setTotal(session, resistorId, "30", "35", "Spis kwartalny"),
				"Stan części zmienił się w międzyczasie i wynosi teraz 40.");

		assertThat(stockOf(resistorId)).isEqualTo(40);
		assertThat(html).contains("Stan: <strong>40</strong>");
		assertThat(html).contains("name=\"expectedQuantity\" value=\"40\"");
		assertThat(html).contains("name=\"newQuantity\" value=\"30\"");
		assertThat(html).contains("value=\"Spis kwartalny\"");
	}

	@Test
	void missingOrNonNumericExpectedQuantityIsTreatedAsStale() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(setTotal(session, resistorId, "30", "abc", "Spis"),
				"Stan części zmienił się w międzyczasie i wynosi teraz 40.");
		assertRejected(post("/parts/{id}/correction/set", resistorId).session(session)
			.with(csrf())
			.param("newQuantity", "30")
			.param("reason", "Spis"), "Stan części zmienił się w międzyczasie i wynosi teraz 40.");
	}

	@Test
	void adjustBelowZeroIsRejectedNamingTheCurrentStock() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = assertRejected(adjustBy(session, resistorId, "-41", "Spis"),
				"Stan nie może spaść poniżej zera: obecny stan to 40.");

		assertThat(html).contains("name=\"delta\" value=\"-41\"");
		assertThat(html).contains("value=\"Spis\"");
	}

	@Test
	void adjustToExactlyZeroIsAccepted() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertApplied(adjustBy(session, resistorId, "-40", "Wszystko zużyte"),
				"Skorygowano stan części „Rezystor 10k”: 40 → 0.");
		assertThat(stockOf(resistorId)).isZero();
	}

	@Test
	void zeroDeltaIsRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(adjustBy(session, resistorId, "0", "Spis"), "Zmiana nie może wynosić 0.");
		assertRejected(adjustBy(session, resistorId, "-0", "Spis"), "Zmiana nie może wynosić 0.");
	}

	@Test
	void setEqualToCurrentStockIsRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(setTotal(session, resistorId, "40", "40", "Spis"),
				"Nowy stan jest równy obecnemu (40). Nie ma czego korygować.");
	}

	@Test
	void blankReasonIsRejectedOnBothForms() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String setHtml = assertRejected(setTotal(session, resistorId, "30", "40", "   "), "Podaj powód korekty.");
		assertThat(setHtml).contains("name=\"newQuantity\" value=\"30\"");
		String adjustHtml = assertRejected(adjustBy(session, resistorId, "3", ""), "Podaj powód korekty.");
		assertThat(adjustHtml).contains("name=\"delta\" value=\"3\"");
	}

	@Test
	void reasonLongerThan500CharactersIsRejectedAnd500IsAccepted() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(setTotal(session, resistorId, "30", "40", "x".repeat(501)),
				"Powód może mieć maksymalnie 500 znaków.");
		assertRejected(adjustBy(session, resistorId, "3", "x".repeat(501)),
				"Powód może mieć maksymalnie 500 znaków.");

		assertApplied(adjustBy(session, resistorId, "3", "  " + "x".repeat(500) + "  "),
				"Skorygowano stan części „Rezystor 10k”: 40 → 43.");
		assertThat(correctionsOf(resistorId).get(0)).containsEntry("reason", "x".repeat(500));
	}

	@Test
	void nonIntegerQuantitiesAreRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(setTotal(session, resistorId, "abc", "40", "Spis"), "Nowy stan musi być liczbą całkowitą.");
		assertRejected(setTotal(session, resistorId, "", "40", "Spis"), "Nowy stan musi być liczbą całkowitą.");
		assertRejected(adjustBy(session, resistorId, "1.5", "Spis"), "Zmiana musi być liczbą całkowitą.");
		assertRejected(adjustBy(session, resistorId, "", "Spis"), "Zmiana musi być liczbą całkowitą.");
	}

	@Test
	void outOfRangeQuantitiesAreRejected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(adjustBy(session, resistorId, "1000001", "Spis"),
				"Zmiana nie może przekraczać 1000000 szt. w żadną stronę.");
		assertRejected(adjustBy(session, resistorId, "-1000001", "Spis"),
				"Zmiana nie może przekraczać 1000000 szt. w żadną stronę.");
		assertRejected(adjustBy(session, resistorId, "99999999999999999999", "Spis"),
				"Zmiana nie może przekraczać 1000000 szt. w żadną stronę.");
		assertRejected(setTotal(session, resistorId, "-1", "40", "Spis"), "Nowy stan nie może być ujemny.");
		assertRejected(setTotal(session, resistorId, "2147483648", "40", "Spis"),
				"Nowy stan nie może przekraczać 2147483647.");
	}

	@Test
	void adjustOverflowingStockIsRejected() throws Exception {
		Long fullId = seedPart("Pełny magazyn", Integer.MAX_VALUE - 1, true, "Regał F1");
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertRejected(adjustBy(session, fullId, "2", "Spis"), "Stan części przekroczyłby dopuszczalny zakres.");
		assertThat(stockOf(fullId)).isEqualTo(Integer.MAX_VALUE - 1);
	}

	/**
	 * A throwaway trigger makes the correction insert fail with a unique
	 * violation at flush, which must roll back the stock change too and
	 * re-render the page instead of surfacing a 500.
	 */
	@Test
	void constraintViolationDuringWriteReRendersWithSaveFailedAndWritesNothing() throws Exception {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_correction_conflict() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					RAISE unique_violation USING MESSAGE = 'simulated correction conflict';
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_correction_conflict_trigger BEFORE INSERT ON stock_corrections "
				+ "FOR EACH ROW EXECUTE FUNCTION test_correction_conflict()");
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		String html = assertRejected(setTotal(session, resistorId, "30", "40", "Spis"),
				"Nie udało się zapisać korekty. Spróbuj ponownie.");

		assertThat(stockOf(resistorId)).isEqualTo(40);
		assertThat(html).contains("Stan: <strong>40</strong>");
	}

	@Test
	void inactivePartCanBeCorrected() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		assertApplied(adjustBy(session, inactiveId, "2", "Znalezione"),
				"Skorygowano stan części „Stary układ”: 3 → 5.");
		assertThat(stockOf(inactiveId)).isEqualTo(5);
		assertApplied(setTotal(session, inactiveId, "0", "5", "Złom"),
				"Skorygowano stan części „Stary układ”: 5 → 0.");
		assertThat(stockOf(inactiveId)).isZero();
		assertThat(correctionsOf(inactiveId)).hasSize(2);
	}

	@Test
	void unknownPartGets404OnBothPostRoutes() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(setTotal(session, Long.MAX_VALUE, "30", "40", "Spis")).andExpect(status().isNotFound());
		mockMvc.perform(adjustBy(session, Long.MAX_VALUE, "3", "Spis")).andExpect(status().isNotFound());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_corrections", Integer.class)).isZero();
	}

	@Test
	void technicianGets403OnBothPostRoutesAndNothingIsWritten() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);
		String before = snapshot();

		mockMvc.perform(setTotal(session, resistorId, "30", "40", "Spis")).andExpect(status().isForbidden());
		mockMvc.perform(adjustBy(session, resistorId, "-3", "Spis")).andExpect(status().isForbidden());

		assertThat(snapshot()).isEqualTo(before);
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

	/**
	 * The text of each {@code <td>} in the first table row containing
	 * {@code text}, tags stripped and whitespace collapsed, so an assertion
	 * pins a value to its row and column instead of anywhere on the page.
	 */
	private static List<String> rowCells(String html, String text) {
		Matcher row = Pattern.compile("<tr>((?:(?!</tr>).)*?" + Pattern.quote(text) + "(?:(?!</tr>).)*?)</tr>",
				Pattern.DOTALL).matcher(html);
		assertThat(row.find()).as("table row containing %s", text).isTrue();
		Matcher cell = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(row.group(1));
		List<String> cells = new ArrayList<>();
		while (cell.find()) {
			cells.add(cell.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip());
		}
		return cells;
	}

}

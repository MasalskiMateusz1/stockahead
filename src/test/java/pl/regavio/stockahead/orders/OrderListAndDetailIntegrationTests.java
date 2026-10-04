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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

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

	private static final String ASSIGNEE_EMAIL = "order-list-detail-assignee@example.com";

	private static final String INACTIVE_ASSIGNEE_EMAIL = "order-list-detail-inactive-assignee@example.com";

	private static final String PASSWORD = "correct-password";

	/** Report/completion moments render as date + minute, no seconds or zone. */
	private static final String MINUTE_PATTERN = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}";

	private static final String PARTIAL_NOTE = "Pozostałe sztuki nie zostaną zbudowane";

	private static final String NOT_RECORDED = "liczba zbudowanych sztuk nie została zapisana";

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
		accountRepository.findByEmail(ASSIGNEE_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(INACTIVE_ASSIGNEE_EMAIL).ifPresent(accountRepository::delete);
	}

	// ---- fixtures -----------------------------------------------------

	private void seedAccount(String email, Role role) {
		seedAccount(email, role, true);
	}

	private Long seedAccount(String email, Role role, boolean active) {
		return transactionTemplate.execute(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(active);
			account.setCreatedAt(Instant.now());
			return accountRepository.save(account).getId();
		});
	}

	private void assignOrder(Long orderId, Long assigneeId) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate
			.update("UPDATE orders SET assignee_id = ? WHERE id = ?", assigneeId, orderId));
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

	/**
	 * A taken order with a completion report by {@code reporterEmail} and the
	 * given built count ({@code null} stands in for a report made before V12),
	 * left pending or already {@code COMPLETED}.
	 */
	private Long seedReportedOrder(Long projectId, int quantityUnits, Integer builtUnits, boolean completed,
			String reporterEmail) {
		Long reporterId = accountRepository.findByEmail(reporterEmail).orElseThrow().getId();
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"""
				INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at, taken_at,
					completion_reported_at, completion_reported_by, built_units, status, completed_at)
				VALUES (?, ?, 'NORMAL', ?, now(), now(), now(), ?, ?, ?, ?)
				RETURNING id
				""",
				Long.class, projectId, quantityUnits, LocalDate.now().plusDays(7), reporterId, builtUnits,
				completed ? "COMPLETED" : "OPEN", completed ? Timestamp.from(Instant.now()) : null));
	}

	// ---- assignee display -------------------------------------------------

	@Test
	void detailShowsAssigneeEmailInactiveMarkerOrDash() throws Exception {
		MockHttpSession session = managerSession();
		Long activeId = seedAccount(ASSIGNEE_EMAIL, Role.TECHNICIAN, true);
		Long inactiveId = seedAccount(INACTIVE_ASSIGNEE_EMAIL, Role.TECHNICIAN, false);
		Long assigned = seedOrder(seedProject("Assigned Board", true), 1, "NORMAL", LocalDate.now().plusDays(3));
		assignOrder(assigned, activeId);
		Long deactivated = seedOrder(seedProject("Deactivated Board", true), 1, "NORMAL",
				LocalDate.now().plusDays(3));
		assignOrder(deactivated, inactiveId);
		Long unassigned = seedOrder(seedProject("Unassigned Board", true), 1, "NORMAL", LocalDate.now().plusDays(3));

		mockMvc.perform(get("/orders/" + assigned).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<dt>Technik:</dt>")))
			.andExpect(content().string(containsString("<dd>" + ASSIGNEE_EMAIL + "</dd>")));
		mockMvc.perform(get("/orders/" + deactivated).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<dd>" + INACTIVE_ASSIGNEE_EMAIL + " (nieaktywny)</dd>")));
		mockMvc.perform(get("/orders/" + unassigned).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(matchesPattern("(?s).*<dt>Technik:</dt>\\s*<dd>—</dd>.*")));
	}

	@Test
	void listsShowAssigneeEmailInactiveMarkerOrDash() throws Exception {
		MockHttpSession session = managerSession();
		Long activeId = seedAccount(ASSIGNEE_EMAIL, Role.TECHNICIAN, true);
		Long inactiveId = seedAccount(INACTIVE_ASSIGNEE_EMAIL, Role.TECHNICIAN, false);
		assignOrder(seedOrder(seedProject("Assigned Board", true), 1, "NORMAL", LocalDate.now().plusDays(3)),
				activeId);
		assignOrder(seedOrder(seedProject("Deactivated Board", true), 1, "NORMAL", LocalDate.now().plusDays(3)),
				inactiveId);
		seedOrder(seedProject("Unassigned Board", true), 1, "NORMAL", LocalDate.now().plusDays(3));
		Long pending = seedReportedOrder(seedProject("Pending Board", true), 4, 4, false, MANAGER_EMAIL);
		assignOrder(pending, activeId);

		String html = mockMvc.perform(get("/orders").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		String pendingSection = html.substring(html.indexOf("id=\"pending-orders\""), html.indexOf("id=\"open-orders\""));
		String openSection = html.substring(html.indexOf("id=\"open-orders\""));
		assertThat(pendingSection).contains("<th>Technik</th>");
		assertThat(pendingSection).contains("<td>" + ASSIGNEE_EMAIL + "</td>");
		assertThat(openSection).contains("<th>Technik</th>");
		assertThat(openSection).contains("<td>" + ASSIGNEE_EMAIL + "</td>");
		assertThat(openSection).contains("<td>" + INACTIVE_ASSIGNEE_EMAIL + " (nieaktywny)</td>");
		assertThat(openSection).containsPattern("(?s)<td>Unassigned Board</td>((?!</tr>).)*<td>—</td>");
	}

	// ---- built count display ----------------------------------------------

	@Test
	void pendingListShowsBuiltCountOrDashForALegacyReport() throws Exception {
		MockHttpSession session = managerSession();
		seedReportedOrder(seedProject("Partial Pending Board", true), 10, 7, false, MANAGER_EMAIL);
		seedReportedOrder(seedProject("Legacy Pending Board", true), 4, null, false, MANAGER_EMAIL);

		String html = mockMvc.perform(get("/orders").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<th>Zbudowano</th>");
		assertThat(html).contains("<td>7 / 10</td>");
		assertThat(html).contains("<td>—</td>");
	}

	@Test
	void pendingDetailShowsBuiltCountAndPartialNote() throws Exception {
		MockHttpSession session = managerSession();
		Long partial = seedReportedOrder(seedProject("Partial Review Board", true), 10, 7, false, MANAGER_EMAIL);
		Long full = seedReportedOrder(seedProject("Full Review Board", true), 10, 10, false, MANAGER_EMAIL);
		Long legacy = seedReportedOrder(seedProject("Legacy Review Board", true), 10, null, false, MANAGER_EMAIL);

		mockMvc.perform(get("/orders/" + partial).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zbudowano: 7 z 10")))
			.andExpect(content().string(containsString(PARTIAL_NOTE)));
		mockMvc.perform(get("/orders/" + full).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zbudowano: 10 z 10")))
			.andExpect(content().string(not(containsString(PARTIAL_NOTE))));
		mockMvc.perform(get("/orders/" + legacy).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zbudowano: — (liczba zbudowanych sztuk nie została zapisana)")))
			.andExpect(content().string(not(containsString(PARTIAL_NOTE))));
	}

	@Test
	void completedDetailShowsFullPartialOrUnknownOutcome() throws Exception {
		MockHttpSession session = managerSession();
		Long partial = seedReportedOrder(seedProject("Partial Done Board", true), 10, 7, true, MANAGER_EMAIL);
		Long full = seedReportedOrder(seedProject("Full Done Board", true), 10, 10, true, MANAGER_EMAIL);
		Long legacy = seedReportedOrder(seedProject("Legacy Done Board", true), 10, null, true, MANAGER_EMAIL);

		mockMvc.perform(get("/orders/" + partial).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(
					matchesPattern("(?s).*<p>Zakończone częściowo: zbudowano 7 z 10, " + MINUTE_PATTERN + "</p>.*")));
		mockMvc.perform(get("/orders/" + full).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(matchesPattern("(?s).*<p>Zakończone " + MINUTE_PATTERN + "</p>.*")))
			.andExpect(content().string(not(containsString("Zakończone częściowo"))))
			.andExpect(content().string(not(containsString(NOT_RECORDED))));
		mockMvc.perform(get("/orders/" + legacy).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(
					matchesPattern("(?s).*<p>Zakończone " + MINUTE_PATTERN + " — " + NOT_RECORDED + "</p>.*")))
			.andExpect(content().string(not(containsString("Zakończone częściowo"))));
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

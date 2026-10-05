package pl.regavio.stockahead.orders;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers {@code PickingController}'s Phase 4 routes: {@code GET /picking}
 * (the OPEN-order list, sorted by {@link ReservationAllocator#ALLOCATION_ORDER})
 * and {@code GET /picking/{id}} (the per-order picking checklist served by
 * {@code PickingDetailModel}). Unlike the equivalent {@code /orders} routes,
 * both MANAGER and TECHNICIAN are allowed on {@code /picking} (see
 * {@code PickingController}'s {@code @PreAuthorize}), so this class asserts
 * {@code 200} for both roles rather than a 403 split. Orders and their lines
 * are seeded directly via {@code JdbcTemplate} rather than through
 * {@code POST /orders}, mirroring {@code OrderListAndDetailIntegrationTests}.
 * Against a real Postgres via Testcontainers, deliberately not
 * {@code @Transactional} so the DB constraints genuinely fire.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class PickingListAndDetailIntegrationTests {

	private static final String MANAGER_EMAIL = "picking-list-detail-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "picking-list-detail-technician@example.com";

	private static final String PASSWORD = "correct-password";

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
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private Company company() {
		if (company == null) {
			company = companyFixtures.company("PickingListAndDetailIntegrationTests Co");
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

	private Long seedProject(String name, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, ?) RETURNING id", Long.class, name, active));
	}

	private Long seedPart(String name, int quantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
				quantity));
	}

	private void seedPartLocation(Long partId, String location) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"INSERT INTO part_locations (part_id, location) VALUES (?, ?)", partId, location));
	}

	private Long seedOrder(Long projectId, int quantityUnits, String priority, LocalDate requiredDate) {
		return seedOrder(projectId, quantityUnits, priority, requiredDate, null);
	}

	/** As above, assigned to {@code assigneeId} ({@code null} leaves it unassigned). */
	private Long seedOrder(Long projectId, int quantityUnits, String priority, LocalDate requiredDate,
			Long assigneeId) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"""
				INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at, assignee_id)
				VALUES (?, ?, ?, ?, now(), ?)
				RETURNING id
				""",
				Long.class, projectId, quantityUnits, priority, requiredDate, assigneeId));
	}

	private Long accountIdOf(String email) {
		return accountRepository.findByEmail(email).orElseThrow().getId();
	}

	private void seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity,
			int pickedQuantity) {
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"""
				INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity, picked_quantity)
				VALUES (?, ?, ?, ?, ?)
				""",
				orderId, partId, requiredQuantity, reservedQuantity, pickedQuantity));
	}

	// ---- detail rendering -------------------------------------------------

	/**
	 * Picks partially through the real {@code POST /picking/.../pick} route
	 * (rather than seeding {@code reserved_quantity}/{@code picked_quantity}
	 * independently via raw SQL, which can describe a combination the real
	 * pick flow could never produce) so the detail page is checked against a
	 * genuinely reachable state. Deliberately picks MORE THAN HALF of the
	 * reservation (4 of 6): {@code remainingToPick} must come from the
	 * current, already-decremented {@code reservedQuantity} alone (2) —
	 * {@code reservedQuantity - pickedQuantity} (2 - 4 = -2) would go
	 * negative and, per the template's {@code th:if}, hide the pick form
	 * entirely even though 2 units are genuinely still pickable. The form's
	 * presence is the discriminating assertion below.
	 */
	@Test
	void detailShowsPartLocationsAndRequiredReservedPickedNumbers() throws Exception {
		Long projectId = seedProject("Picking Board", true);
		Long resistorId = seedPart("Resistor 10k", 6);
		seedPartLocation(resistorId, "Hala A / Regał 3");
		Long orderId = seedOrder(projectId, 1, "HIGH", LocalDate.now().plusDays(7));
		seedOrderLine(orderId, resistorId, 10, 6, 0);
		Long lineId = lineIdOf(orderId);

		MockHttpSession session = managerSession();

		mockMvc.perform(post("/picking/" + orderId + "/lines/" + lineId + "/pick").session(session)
			.with(csrf())
			.param("quantity", "4"))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/picking/" + orderId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Picking Board")))
			.andExpect(content().string(containsString("Resistor 10k")))
			.andExpect(content().string(containsString("Hala A / Regał 3")))
			.andExpect(content().string(containsString("<td>10</td>")))
			.andExpect(content().string(containsString("<td>2</td>")))
			.andExpect(content().string(containsString("<td>4</td>")))
			.andExpect(content().string(containsString("/lines/" + lineId + "/pick")));
	}

	/**
	 * The report form carries a built-count input pre-filled with the order's
	 * unit count and bounded by it; once reported (through the real pick and
	 * report routes) the page shows the reported count against that total.
	 */
	@Test
	void detailPrefillsBuiltCountAndShowsReportedCount() throws Exception {
		Long projectId = seedProject("Built Count Board", true);
		Long partId = seedPart("Built Count Capacitor", 8);
		Long orderId = seedOrder(projectId, 4, "NORMAL", LocalDate.now().plusDays(7));
		seedOrderLine(orderId, partId, 8, 8, 0);
		Long lineId = lineIdOf(orderId);
		MockHttpSession technician = technicianSession();

		mockMvc.perform(post("/picking/" + orderId + "/lines/" + lineId + "/pick").session(technician)
			.with(csrf())
			.param("quantity", "8"))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/picking/" + orderId).session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zbudowano sztuk")))
			.andExpect(content().string(
					containsString("name=\"builtUnits\" min=\"0\" max=\"4\" required")))
			.andExpect(content().string(containsString("value=\"4\"")))
			.andExpect(content().string(not(containsString("zbudowano"))));

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderId).session(technician)
			.with(csrf())
			.param("builtUnits", "3"))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/picking/" + orderId).session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Zgłoszone do potwierdzenia — zbudowano 3 z 4")))
			.andExpect(content().string(not(containsString("name=\"builtUnits\""))));
	}

	private Long lineIdOf(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, orderId);
	}

	// ---- list smoke test ------------------------------------------------

	@Test
	void managerSeesSeededOrderOnThePickingList() throws Exception {
		Long projectId = seedProject("Sensor Board", true);
		Long sensorId = seedPart("Sensor A", 3);
		Long orderId = seedOrder(projectId, 2, "NORMAL", LocalDate.now().plusDays(3));
		seedOrderLine(orderId, sensorId, 5, 3, 0);

		MockHttpSession session = managerSession();

		mockMvc.perform(get("/picking").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Sensor Board")));
	}

	// ---- "Do pobrania" column ----------------------------------------------

	/**
	 * Pins the list's per-order "Do pobrania" count: the sum of the order's
	 * live {@code reservedQuantity} (already net of picks). Every state is
	 * reached through the real pick, report and confirm routes. Order B is a
	 * taken, short order (needs 8, holds 4); after A is confirmed the
	 * allocator tops B up, and its cell must rise from 3 to 7, then drop to 5
	 * after a second real pick on the same line. Each assertion targets the
	 * row's own {@code to-pick-<orderId>} cell, so another order's count can't
	 * satisfy it.
	 */
	@Test
	void pickingListShowsUnitsToPickPerOrderIncludingToppedUpTakenOrder() throws Exception {
		MockHttpSession manager = managerSession();
		MockHttpSession technician = technicianSession();
		Long technicianId = accountIdOf(TECHNICIAN_EMAIL);
		Long partId = seedPart("To-Pick Resistor", 10);
		Long orderA = seedOrder(seedProject("To-Pick First Board", true), 1, "HIGH", LocalDate.now().plusDays(3),
				technicianId);
		seedOrderLine(orderA, partId, 6, 6, 0);
		Long lineA = lineIdOf(orderA);
		Long orderB = seedOrder(seedProject("To-Pick Short Board", true), 1, "NORMAL",
				LocalDate.now().plusDays(7), technicianId);
		seedOrderLine(orderB, partId, 8, 4, 0);
		Long lineB = lineIdOf(orderB);

		pick(technician, orderA, lineA, 1);
		pick(technician, orderB, lineB, 1);

		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<th>Do pobrania</th>")))
			.andExpect(content().string(containsString(toPickCell(orderA, 5))))
			.andExpect(content().string(containsString(toPickCell(orderB, 3))));

		mockMvc.perform(post("/picking/{orderId}/report-completion", orderA).session(technician).with(csrf()).param("builtUnits", "1"))
			.andExpect(status().is3xxRedirection());
		mockMvc.perform(post("/orders/{id}/confirm-completion", orderA).session(manager).with(csrf()))
			.andExpect(status().is3xxRedirection());

		// Pool after confirm: stock 8 - B's protected 3 = 5; B is short by 8 - 1 - 3 = 4.
		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("id=\"to-pick-" + orderA + "\""))))
			.andExpect(content().string(containsString(toPickCell(orderB, 7))));

		pick(technician, orderB, lineB, 2);

		mockMvc.perform(get("/picking").session(technician))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(toPickCell(orderB, 5))));
	}

	private void pick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity)))
			.andExpect(status().is3xxRedirection());
	}

	private static String toPickCell(Long orderId, int count) {
		return "<td id=\"to-pick-" + orderId + "\">" + count + "</td>";
	}

	// ---- both roles allowed -----------------------------------------------

	@Test
	void bothManagerAndTechnicianGet200OnPickingListAndDetailRoutes() throws Exception {
		Long projectId = seedProject("Shared Access Board", true);
		Long partId = seedPart("Shared Part", 3);
		Long orderId = seedOrder(projectId, 1, "LOW", LocalDate.now().plusDays(1));
		seedOrderLine(orderId, partId, 3, 1, 0);

		MockHttpSession managerSession = managerSession();
		mockMvc.perform(get("/picking").session(managerSession)).andExpect(status().isOk());
		mockMvc.perform(get("/picking/" + orderId).session(managerSession)).andExpect(status().isOk());

		MockHttpSession technicianSession = technicianSession();
		mockMvc.perform(get("/picking").session(technicianSession)).andExpect(status().isOk());
		mockMvc.perform(get("/picking/" + orderId).session(technicianSession)).andExpect(status().isOk());
	}

	// ---- not found --------------------------------------------------------

	@Test
	void unknownOrderIdIs404() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(get("/picking/999999").session(session)).andExpect(status().isNotFound());
	}

}

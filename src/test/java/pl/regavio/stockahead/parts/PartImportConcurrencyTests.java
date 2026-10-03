package pl.regavio.stockahead.parts;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.orders.Priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Proves {@code PartImportController}'s confirm keeps the hard rules (stock
 * never below zero, no unit reserved twice, no lost update) and the catalog's
 * case-insensitive name uniqueness when it races the other stock and catalog
 * writers — a delivery receipt, a pick, a new order, a manual part creation
 * and another import — against real Postgres via Testcontainers. Mirrors
 * {@code DeliveryConcurrencyTests}: raw SQL fixtures, the real HTTP routes
 * through {@code MockMvc} with logged-in {@link MockHttpSession}s (each
 * import previewed before the race, so only its confirm races), two threads
 * released together by a {@link CountDownLatch} with alternating start
 * offsets, and throwaway {@code pg_sleep} triggers that widen the windows.
 *
 * <p>
 * The triggers slow every stock-changing {@code UPDATE} of {@code parts},
 * every {@code INSERT} into {@code parts}, {@code part_locations} and
 * {@code orders}, so each writer stays inside its lock-to-commit window well
 * past the moment the competing request reads the same part, name or shelf.
 * Every race asserts that the two requests' wall-clock intervals overlapped,
 * so no scenario passes only because its threads ran one after the other.
 * Where the result depends on which request wins (an import that sees a
 * changed catalog writes nothing and shows a refreshed preview), the test
 * accepts exactly the two serial outcomes, tells them apart by the import's
 * response, and then accepts the refreshed preview to show that the final
 * state is the same either way.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PartImportConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "import-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "import-concurrency-manager@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

	private static final String STALE_NOTICE = "Stany zmieniły się od podglądu — sprawdź i zatwierdź ponownie.";

	private static final int ITERATIONS = 4;

	private static final Pattern TABLE_ROW = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);

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

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		installSlowdowns();
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		projectId = seedProject("Import Concurrency Board");
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropSlowdowns();
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

	/**
	 * A stock change on {@code parts}, a new part, a new location and a new
	 * order each sleep before landing, so the writing transaction (holding its
	 * shelf, name and part-row locks) stays open well past the moment a
	 * competing request tries to read the same state.
	 */
	private void installSlowdowns() {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_import_slow_write() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.2);
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_import_slow_stock_trigger BEFORE UPDATE ON parts "
				+ "FOR EACH ROW WHEN (NEW.quantity IS DISTINCT FROM OLD.quantity) "
				+ "EXECUTE FUNCTION test_import_slow_write()");
		jdbcTemplate.execute("CREATE TRIGGER test_import_slow_part_trigger BEFORE INSERT ON parts "
				+ "FOR EACH ROW EXECUTE FUNCTION test_import_slow_write()");
		jdbcTemplate.execute("CREATE TRIGGER test_import_slow_location_trigger BEFORE INSERT ON part_locations "
				+ "FOR EACH ROW EXECUTE FUNCTION test_import_slow_write()");
		jdbcTemplate.execute("CREATE TRIGGER test_import_slow_order_trigger BEFORE INSERT ON orders "
				+ "FOR EACH ROW EXECUTE FUNCTION test_import_slow_write()");
	}

	private void dropSlowdowns() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_import_slow_stock_trigger ON parts");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_import_slow_part_trigger ON parts");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_import_slow_location_trigger ON part_locations");
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_import_slow_order_trigger ON orders");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_import_slow_write()");
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

	/**
	 * Logs in and returns a fresh session. Only one MANAGER account may exist,
	 * so a race between two manager-only routes uses two separate logins of
	 * that account; each session holds its own pending import.
	 */
	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedProject(String name) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, true) RETURNING id", Long.class, name));
	}

	private Long seedPart(String name, int quantity, String... locations) {
		return transactionTemplate.execute(status -> {
			Long id = jdbcTemplate.queryForObject(
					"INSERT INTO parts (name, quantity, active) VALUES (?, ?, true) RETURNING id", Long.class, name,
					quantity);
			for (String location : locations) {
				jdbcTemplate.update("INSERT INTO part_locations (part_id, location) VALUES (?, ?)", id, location);
			}
			return id;
		});
	}

	private Long seedBomLine(Long project, Long part, int quantityPerUnit) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?) RETURNING id",
				Long.class, project, part, quantityPerUnit));
	}

	private Long seedOrder(Long orderProjectId, Priority priority, LocalDate requiredDate, Instant createdAt) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO orders (project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, 1, ?, ?, ?) RETURNING id",
				Long.class, orderProjectId, priority.name(), requiredDate, Timestamp.from(createdAt)));
	}

	private Long seedOrderLine(Long orderId, Long partId, int requiredQuantity, int reservedQuantity) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, orderId, partId, requiredQuantity, reservedQuantity));
	}

	private void deleteParts() {
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
	}

	// ---- requests -----------------------------------------------------

	/** Uploads {@code rows} (after the header) and expects the preview, leaving the pending import in the session. */
	private void previewImport(MockHttpSession session, String rows) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "czesci.csv", "text/csv",
				(HEADER + rows).getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(multipart("/parts/import").file(file).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name(PartImportController.PREVIEW_VIEW));
	}

	/** Confirms the session's pending import with its own token. */
	private MvcResult confirmImport(MockHttpSession session) throws Exception {
		String token = pendingOf(session).token();
		return mockMvc.perform(post("/parts/import/confirm").session(session).with(csrf()).param("token", token))
			.andReturn();
	}

	private static PendingImport pendingOf(MockHttpSession session) {
		return (PendingImport) session.getAttribute(PendingImport.SESSION_ATTRIBUTE);
	}

	private MvcResult postReceipt(MockHttpSession session, Long partId, int quantity) throws Exception {
		return mockMvc.perform(post("/deliveries").session(session)
			.with(csrf())
			.param("rows", "5")
			.param("partId0", partId.toString())
			.param("quantity0", Integer.toString(quantity))).andReturn();
	}

	private MvcResult postPick(MockHttpSession session, Long orderId, Long lineId, int quantity) throws Exception {
		return mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", orderId, lineId).session(session)
			.with(csrf())
			.param("quantity", Integer.toString(quantity))).andReturn();
	}

	private MvcResult postCreatePart(MockHttpSession session, String name, int quantity, String locations)
			throws Exception {
		return mockMvc.perform(post("/parts").session(session)
			.with(csrf())
			.param("name", name)
			.param("quantity", Integer.toString(quantity))
			.param("locations", locations)).andReturn();
	}

	// ---- state --------------------------------------------------------

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

	private Long lineOf(Long orderId, Long partId) {
		return jdbcTemplate.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND part_id = ?",
				Long.class, orderId, partId);
	}

	private int partCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM parts", Integer.class);
	}

	private String onlyPartName() {
		return jdbcTemplate.queryForObject("SELECT name FROM parts", String.class);
	}

	private int onlyPartStock() {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts", Integer.class);
	}

	private List<String> onlyPartLocations() {
		return jdbcTemplate.queryForList("SELECT location FROM part_locations ORDER BY location", String.class);
	}

	private int spellingsOf(String shelf) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT location) FROM part_locations WHERE lower(location) = lower(?)", Integer.class,
				shelf);
	}

	/**
	 * The part's invariants, as SQL over {@code parts}/{@code order_lines}:
	 * stock is non-negative; the live reservations on the part (net of picks)
	 * never exceed its stock, so no unit is reserved twice; and no line holds
	 * more than it requires.
	 */
	private void assertInvariants(Long partId, int iteration) {
		int stock = stockOf(partId);
		Integer totalReserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE part_id = ?", Integer.class,
				partId);
		Integer overfilledLines = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM order_lines WHERE part_id = ? AND picked_quantity + reserved_quantity > required_quantity",
				Integer.class, partId);
		assertThat(stock).as("iteration %d: stock", iteration).isNotNegative();
		assertThat(totalReserved).as("iteration %d: reserved vs stock", iteration).isLessThanOrEqualTo(stock);
		assertThat(overfilledLines).as("iteration %d: lines over required", iteration).isZero();
	}

	// ---- racing -------------------------------------------------------

	/** One raced request's result and its wall-clock interval (after its start offset). */
	private record Timed(MvcResult result, long startNanos, long endNanos) {
	}

	/**
	 * Runs both requests on two threads released together, each after its
	 * start offset, returning their timed results in order.
	 */
	private Timed[] race(long firstDelayMillis, Callable<MvcResult> first, long secondDelayMillis,
			Callable<MvcResult> second) throws Exception {
		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Timed> firstFuture = executor.submit(() -> timed(bothReady, firstDelayMillis, first));
			Future<Timed> secondFuture = executor.submit(() -> timed(bothReady, secondDelayMillis, second));
			return new Timed[] { firstFuture.get(20, TimeUnit.SECONDS), secondFuture.get(20, TimeUnit.SECONDS) };
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static Timed timed(CountDownLatch bothReady, long delayMillis, Callable<MvcResult> request)
			throws Exception {
		bothReady.countDown();
		bothReady.await(5, TimeUnit.SECONDS);
		Thread.sleep(delayMillis);
		long start = System.nanoTime();
		MvcResult result = request.call();
		return new Timed(result, start, System.nanoTime());
	}

	/**
	 * Each request started before the other one finished: the two were in
	 * flight at the same time, so the race was real rather than sequential.
	 */
	private static void assertOverlapped(Timed[] results, int iteration) {
		assertThat(results[1].startNanos()).as("iteration %d: second started before first finished", iteration)
			.isLessThan(results[0].endNanos());
		assertThat(results[0].startNanos()).as("iteration %d: first started before second finished", iteration)
			.isLessThan(results[1].endNanos());
	}

	/** Alternates both starting together and each one arriving while the other is inside its slowed write. */
	private static long firstDelay(int iteration) {
		return iteration % 3 == 1 ? 50 : 0;
	}

	private static long secondDelay(int iteration) {
		return iteration % 3 == 2 ? 50 : 0;
	}

	private static boolean applied(MvcResult result) {
		return result.getResponse().getStatus() == 302;
	}

	private static void assertRedirectedTo(MvcResult result, String location, String what, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: %s status", iteration, what).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).as("iteration %d: %s redirect", iteration, what)
			.isEqualTo(location);
	}

	/**
	 * The import wrote nothing and showed its refreshed preview with the
	 * staleness notice; returns the page.
	 */
	private static String assertRePreviewed(MvcResult result, int iteration) throws Exception {
		assertThat(result.getResponse().getStatus()).as("iteration %d: import status", iteration).isEqualTo(200);
		assertThat(result.getModelAndView().getViewName()).as("iteration %d: import view", iteration)
			.isEqualTo(PartImportController.PREVIEW_VIEW);
		String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).as("iteration %d: staleness notice", iteration).contains(STALE_NOTICE);
		return html;
	}

	/**
	 * The text of the preview table row whose cells contain {@code name}, tags
	 * stripped and whitespace (non-breaking spaces included) collapsed.
	 */
	private static String rowOf(String html, String name) {
		Matcher matcher = TABLE_ROW.matcher(html);
		while (matcher.find()) {
			String text = matcher.group(1).replaceAll("<[^>]+>", " ").replaceAll("[\\s\\u00A0]+", " ").trim();
			if (text.contains(name)) {
				return text;
			}
		}
		throw new AssertionError("no preview row for " + name + " in:\n" + html);
	}

	// ---- tests ----------------------------------------------------------

	/**
	 * The manager accepts an import of +10 while a technician receives +10 of
	 * the same part (stock 30, a waiting order short of it). If the import
	 * locks the part first, both land: 30 + 10 + 10 = 50. If the receipt
	 * commits first, the import sees stock 40 instead of the previewed 30,
	 * writes nothing and shows the refreshed preview (40 → 50); accepting it
	 * then reaches the same 50. Either way no update is lost and the waiting
	 * order is reallocated from the final stock.
	 */
	@Test
	void importRacingADeliveryNeverLosesAnUpdate() throws Exception {
		MockHttpSession manager = login(MANAGER_EMAIL);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			String name = "Receipt Race Part " + iteration;
			Long partId = seedPart(name, 30, "A1");
			Long waitingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7), Instant.now());
			Long waitingLine = seedOrderLine(waitingOrder, partId, 100, 30);
			previewImport(manager, name + ";10;A1\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(manager), secondDelay(iteration),
					() -> postReceipt(technician, partId, 10));

			assertOverlapped(results, iteration);
			assertRedirectedTo(results[1].result(), "/parts", "receipt", iteration);
			assertInvariants(partId, iteration);
			if (applied(results[0].result())) {
				assertRedirectedTo(results[0].result(), "/parts", "import", iteration);
			}
			else {
				String html = assertRePreviewed(results[0].result(), iteration);
				assertThat(rowOf(html, name)).as("iteration %d: refreshed row", iteration)
					.isEqualTo(name + " 40 10 50");
				assertThat(stockOf(partId)).as("iteration %d: stock after the receipt alone", iteration)
					.isEqualTo(40);
				assertRedirectedTo(confirmImport(manager), "/parts", "second confirm", iteration);
			}
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(50);
			assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isEqualTo(50);
			assertInvariants(partId, iteration);
		}
	}

	/**
	 * An import of +10 races a pick of 3 from a taken HIGH order (required
	 * 10, one unit already picked through the real endpoint: stock 4,
	 * reserved 4) that a LOW order is also waiting on. Stock never goes
	 * negative and reservations never exceed it, after the race and after the
	 * import is applied. If the pick lands first, the import is stale (stock
	 * 1, not the previewed 4) and is accepted again from its refreshed
	 * preview. Either way the end state is the same: stock 4 - 3 + 10 = 11,
	 * taken line picked 4 / reserved 6, waiting line 5.
	 */
	@Test
	void importRacingAPickKeepsStockAndReservationsConsistent() throws Exception {
		MockHttpSession manager = login(MANAGER_EMAIL);
		MockHttpSession technician = login(TECHNICIAN_EMAIL);
		Instant now = Instant.now();

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			String name = "Pick Race Part " + iteration;
			Long partId = seedPart(name, 5, "A1");
			Long takenOrder = seedOrder(projectId, Priority.HIGH, LocalDate.now().plusDays(7), now.minusSeconds(60));
			Long takenLine = seedOrderLine(takenOrder, partId, 10, 5);
			Long waitingOrder = seedOrder(projectId, Priority.LOW, LocalDate.now().plusDays(7), now);
			Long waitingLine = seedOrderLine(waitingOrder, partId, 20, 0);
			mockMvc.perform(post("/picking/{orderId}/lines/{lineId}/pick", takenOrder, takenLine).session(technician)
				.with(csrf())
				.param("quantity", "1"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/picking/" + takenOrder));
			previewImport(manager, name + ";10;A1\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(manager), secondDelay(iteration),
					() -> postPick(technician, takenOrder, takenLine, 3));

			assertOverlapped(results, iteration);
			assertRedirectedTo(results[1].result(), "/picking/" + takenOrder, "pick", iteration);
			assertInvariants(partId, iteration);
			if (!applied(results[0].result())) {
				String html = assertRePreviewed(results[0].result(), iteration);
				assertThat(rowOf(html, name)).as("iteration %d: refreshed row", iteration)
					.isEqualTo(name + " 1 10 11");
				assertThat(stockOf(partId)).as("iteration %d: stock after the pick alone", iteration).isEqualTo(1);
				assertRedirectedTo(confirmImport(manager), "/parts", "second confirm", iteration);
			}
			else {
				assertRedirectedTo(results[0].result(), "/parts", "import", iteration);
			}
			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(11);
			assertThat(pickedOf(takenLine)).as("iteration %d: taken picked", iteration).isEqualTo(4);
			assertThat(reservedOf(takenLine)).as("iteration %d: taken reserved", iteration).isEqualTo(6);
			assertThat(reservedOf(waitingLine)).as("iteration %d: waiting reserved", iteration).isEqualTo(5);
		}
	}

	/**
	 * An import of +5 races the manager (second login) creating a HIGH order
	 * (BOM 8 per unit) on a part a NORMAL order already holds all 5 of. Order
	 * creation changes no stock, so the import is never stale and both land.
	 * Both rebuild the non-taken allocation from the stock they see under the
	 * part lock, so the last one decides it from the final stock of 10: the
	 * new HIGH order gets 8 and the existing one 2, in either order — no unit
	 * reserved twice.
	 */
	@Test
	void importRacingANewOrderLeavesTheAllocationTheRulesGrantFromTheFinalStock() throws Exception {
		MockHttpSession importing = login(MANAGER_EMAIL);
		MockHttpSession ordering = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			String name = "Order Race Part " + iteration;
			Long orderProject = seedProject("Order Race Board " + iteration);
			Long partId = seedPart(name, 5, "A1");
			seedBomLine(orderProject, partId, 8);
			Long existingOrder = seedOrder(projectId, Priority.NORMAL, LocalDate.now().plusDays(7),
					Instant.now().minusSeconds(60));
			Long existingLine = seedOrderLine(existingOrder, partId, 5, 5);
			previewImport(importing, name + ";5;A1\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(importing), secondDelay(iteration),
					() -> mockMvc.perform(post("/orders").session(ordering)
						.with(csrf())
						.param("projectId", orderProject.toString())
						.param("quantityUnits", "1")
						.param("priority", Priority.HIGH.name())
						.param("requiredDate", LocalDate.now().plusDays(7).toString())).andReturn());

			assertOverlapped(results, iteration);
			assertRedirectedTo(results[0].result(), "/parts", "import", iteration);
			MvcResult createResult = results[1].result();
			assertThat(createResult.getResponse().getStatus()).as("iteration %d: create status", iteration)
				.isEqualTo(302);
			String location = createResult.getResponse().getRedirectedUrl();
			assertThat(location).as("iteration %d: create redirect", iteration).startsWith("/orders/");
			Long newLine = lineOf(Long.valueOf(location.substring("/orders/".length())), partId);

			assertInvariants(partId, iteration);
			assertThat(stockOf(partId)).as("iteration %d: stock", iteration).isEqualTo(10);
			assertThat(reservedOf(newLine)).as("iteration %d: new order reserved", iteration).isEqualTo(8);
			assertThat(reservedOf(existingLine)).as("iteration %d: existing order reserved", iteration).isEqualTo(2);
		}
	}

	/** An import creating "Nowa część" races a manual create of the very same name. */
	@Test
	void importAndManualCreateOfTheSameNewPartLeaveOnePart() throws Exception {
		raceImportAgainstManualCreate("Nowa część");
	}

	/** An import creating "Nowa część" races a manual create of the case variant "nowa CZĘŚĆ". */
	@Test
	void importAndManualCreateOfACaseVariantLeaveOnePart() throws Exception {
		raceImportAgainstManualCreate("nowa CZĘŚĆ");
	}

	/**
	 * The manager previews an import creating "Nowa część" (5 on A1) and, in a
	 * second login, creates {@code manualName} by hand (3 on B2). Both take
	 * the part-name lock for the case-folded name, so exactly one part exists
	 * afterwards. If the manual create commits first, the import sees it
	 * under the lock, writes nothing and re-previews the line as that
	 * existing part (3 → 8, adding A1); accepting it raises that part. If the
	 * import commits first, the manual create is rejected with the friendly
	 * duplicate-name error on its form, never a 500.
	 */
	private void raceImportAgainstManualCreate(String manualName) throws Exception {
		MockHttpSession importing = login(MANAGER_EMAIL);
		MockHttpSession creating = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			previewImport(importing, "Nowa część;5;A1\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(importing), secondDelay(iteration),
					() -> postCreatePart(creating, manualName, 3, "B2"));

			assertOverlapped(results, iteration);
			assertThat(partCount()).as("iteration %d: parts after the race", iteration).isEqualTo(1);
			MvcResult importResult = results[0].result();
			MvcResult createResult = results[1].result();
			if (applied(createResult)) {
				assertRedirectedTo(createResult, "/parts", "manual create", iteration);
				String html = assertRePreviewed(importResult, iteration);
				assertThat(rowOf(html, manualName)).as("iteration %d: refreshed row", iteration)
					.isEqualTo(manualName + " 3 5 8 A1");
				assertThat(html).as("iteration %d: no new part in the refreshed preview", iteration)
					.doesNotContain("NOWA");
				assertRedirectedTo(confirmImport(importing), "/parts", "second confirm", iteration);
				assertThat(partCount()).as("iteration %d: parts", iteration).isEqualTo(1);
				assertThat(onlyPartName()).as("iteration %d: name", iteration).isEqualTo(manualName);
				assertThat(onlyPartStock()).as("iteration %d: stock", iteration).isEqualTo(8);
				assertThat(onlyPartLocations()).as("iteration %d: locations", iteration).containsExactly("A1", "B2");
			}
			else {
				assertRedirectedTo(importResult, "/parts", "import", iteration);
				assertThat(createResult.getResponse().getStatus()).as("iteration %d: create status", iteration)
					.isEqualTo(200);
				assertThat(createResult.getModelAndView().getViewName()).as("iteration %d: create view", iteration)
					.isEqualTo("parts-new");
				assertThat(createResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
					.as("iteration %d: create error", iteration)
					.contains("Część o tej nazwie już istnieje.");
				assertThat(onlyPartName()).as("iteration %d: name", iteration).isEqualTo("Nowa część");
				assertThat(onlyPartStock()).as("iteration %d: stock", iteration).isEqualTo(5);
				assertThat(onlyPartLocations()).as("iteration %d: locations", iteration).containsExactly("A1");
			}
			deleteParts();
		}
	}

	/**
	 * Two logins of the manager each preview an import creating the same new
	 * part under case-variant names on different shelves ("Nowa część" 5 on
	 * A1, "nowa CZĘŚĆ" 3 on B2), then accept at once. The part-name lock lets
	 * one create it; the other sees that part under the lock, writes nothing
	 * and re-previews its line as the existing part. Accepting the refreshed
	 * preview raises it: one part, stock 8, both shelves.
	 */
	@Test
	void twoImportsCreatingCaseVariantsOfOneNewPartLeaveOnePart() throws Exception {
		MockHttpSession first = login(MANAGER_EMAIL);
		MockHttpSession second = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			previewImport(first, "Nowa część;5;A1\r\n");
			previewImport(second, "nowa CZĘŚĆ;3;B2\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(first), secondDelay(iteration),
					() -> confirmImport(second));

			assertOverlapped(results, iteration);
			assertThat(partCount()).as("iteration %d: parts after the race", iteration).isEqualTo(1);
			boolean firstWon = applied(results[0].result());
			assertThat(applied(results[1].result())).as("iteration %d: exactly one import applied", iteration)
				.isNotEqualTo(firstWon);
			MockHttpSession loser = firstWon ? second : first;
			MvcResult loserResult = firstWon ? results[1].result() : results[0].result();
			MvcResult winnerResult = firstWon ? results[0].result() : results[1].result();
			String winnerName = firstWon ? "Nowa część" : "nowa CZĘŚĆ";
			String loserRow = firstWon ? winnerName + " 5 3 8 B2" : winnerName + " 3 5 8 A1";

			assertRedirectedTo(winnerResult, "/parts", "winning import", iteration);
			String html = assertRePreviewed(loserResult, iteration);
			assertThat(rowOf(html, winnerName)).as("iteration %d: refreshed row", iteration).isEqualTo(loserRow);
			assertThat(html).as("iteration %d: no new part in the refreshed preview", iteration)
				.doesNotContain("NOWA");
			assertThat(onlyPartName()).as("iteration %d: name", iteration).isEqualTo(winnerName);

			assertRedirectedTo(confirmImport(loser), "/parts", "second confirm", iteration);
			assertThat(partCount()).as("iteration %d: parts", iteration).isEqualTo(1);
			assertThat(onlyPartStock()).as("iteration %d: stock", iteration).isEqualTo(8);
			assertThat(onlyPartLocations()).as("iteration %d: locations", iteration).containsExactly("A1", "B2");
			deleteParts();
		}
	}

	/**
	 * Two logins of the manager each import a different new part onto a shelf
	 * no part holds yet, typed "n9" and "N9", and accept at once. Both land;
	 * the second waits on the shelf lock and then takes the first one's
	 * committed spelling, so the shelf has one spelling.
	 */
	@Test
	void twoImportsIntroducingANewShelfInDifferentCaseStoreOneSpelling() throws Exception {
		MockHttpSession first = login(MANAGER_EMAIL);
		MockHttpSession second = login(MANAGER_EMAIL);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			previewImport(first, "Shelf Part P " + iteration + ";1;n9\r\n");
			previewImport(second, "Shelf Part Q " + iteration + ";1;N9\r\n");

			Timed[] results = race(firstDelay(iteration), () -> confirmImport(first), secondDelay(iteration),
					() -> confirmImport(second));

			assertOverlapped(results, iteration);
			assertRedirectedTo(results[0].result(), "/parts", "first import", iteration);
			assertRedirectedTo(results[1].result(), "/parts", "second import", iteration);
			assertThat(partCount()).as("iteration %d: parts", iteration).isEqualTo(2);
			assertThat(spellingsOf("n9")).as("iteration %d: spellings of shelf N9", iteration).isEqualTo(1);
			deleteParts();
		}
	}

}

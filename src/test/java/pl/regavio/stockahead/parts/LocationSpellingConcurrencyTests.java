package pl.regavio.stockahead.parts;

import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves a shelf keeps one spelling across parts when two writers touch it
 * at once: a case-only respell on part edit racing a receipt, and two part
 * creations introducing a brand-new shelf in different case. Against real
 * Postgres via Testcontainers, through the real HTTP routes, with throwaway
 * {@code pg_sleep} triggers on {@code part_locations} that hold each writer
 * inside its resolve-to-commit window. Without the per-shelf lock taken by
 * {@link LocationSpellings#lockShelves}, the second writer resolves the
 * spelling before the first commits and stores its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LocationSpellingConcurrencyTests {

	private static final String TECHNICIAN_EMAIL = "spelling-concurrency-technician@example.com";

	private static final String MANAGER_EMAIL = "spelling-concurrency-manager@example.com";

	private static final String PASSWORD = "correct-password";

	private static final int ITERATIONS = 4;

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
		installSlowdowns();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		dropSlowdowns();
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
	 * Every insert or update of a {@code part_locations} row sleeps before
	 * landing, so the writing transaction stays open well past the moment a
	 * competing request resolves the same shelf's spelling.
	 */
	private void installSlowdowns() {
		jdbcTemplate.execute("""
				CREATE FUNCTION test_spelling_slow_write() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					PERFORM pg_sleep(0.3);
					RETURN NEW;
				END
				$$""");
		jdbcTemplate.execute("CREATE TRIGGER test_spelling_slow_location_trigger "
				+ "BEFORE INSERT OR UPDATE ON part_locations "
				+ "FOR EACH ROW EXECUTE FUNCTION test_spelling_slow_write()");
	}

	private void dropSlowdowns() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_spelling_slow_location_trigger ON part_locations");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_spelling_slow_write()");
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession sessionFor(String email, Role role) throws Exception {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/** Seeds a part and its locations with raw SQL, before the slowdown matters for the race. */
	private Long seedPart(String name, String... locations) {
		return transactionTemplate.execute(status -> {
			Long partId = jdbcTemplate.queryForObject(
					"INSERT INTO parts (name, quantity, active) VALUES (?, 1, true) RETURNING id", Long.class, name);
			for (String location : locations) {
				jdbcTemplate.update("INSERT INTO part_locations (part_id, location) VALUES (?, ?)", partId, location);
			}
			return partId;
		});
	}

	private int spellingsOf(String shelf) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT location) FROM part_locations WHERE lower(location) = lower(?)", Integer.class,
				shelf);
	}

	/** Runs both requests on two threads released together, returning their results in order. */
	private MvcResult[] race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
		CountDownLatch bothReady = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<MvcResult> firstFuture = executor.submit(() -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				return first.call();
			});
			Future<MvcResult> secondFuture = executor.submit(() -> {
				bothReady.countDown();
				bothReady.await(5, TimeUnit.SECONDS);
				return second.call();
			});
			return new MvcResult[] { firstFuture.get(20, TimeUnit.SECONDS), secondFuture.get(20, TimeUnit.SECONDS) };
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void assertRedirected(MvcResult result, String what, int iteration) {
		assertThat(result.getResponse().getStatus()).as("iteration %d: %s status", iteration, what).isEqualTo(302);
	}

	// ---- tests ----------------------------------------------------------

	/**
	 * Parts X and Z hold shelf "A1". The manager re-spells it to "a1" on X
	 * (which renames it on Z too) while a technician receives part Y into
	 * "A1". Whichever starts first, the other waits on the shelf lock and
	 * then sees the committed spelling, so the shelf ends with one spelling
	 * on all three parts.
	 */
	@Test
	void editRespellingAShelfRacingAReceiptLeavesOneSpelling() throws Exception {
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);
		MockHttpSession technician = sessionFor(TECHNICIAN_EMAIL, Role.TECHNICIAN);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			String nameX = "Respell X " + iteration;
			Long partX = seedPart(nameX, "A1");
			seedPart("Respell Z " + iteration, "A1");
			Long partY = seedPart("Respell Y " + iteration);
			long editDelayMillis = iteration % 2 == 1 ? 100 : 0;
			long receiptDelayMillis = iteration % 2 == 0 ? 100 : 0;

			MvcResult[] results = race(() -> {
				Thread.sleep(editDelayMillis);
				return mockMvc.perform(post("/parts/" + partX).session(manager)
					.with(csrf())
					.param("name", nameX)
					.param("locations", "a1")).andReturn();
			}, () -> {
				Thread.sleep(receiptDelayMillis);
				return mockMvc.perform(post("/deliveries").session(technician)
					.with(csrf())
					.param("rows", "5")
					.param("partId0", partY.toString())
					.param("quantity0", "1")
					.param("location0", "A1")).andReturn();
			});

			assertRedirected(results[0], "edit", iteration);
			assertRedirected(results[1], "receipt", iteration);
			assertThat(spellingsOf("a1")).as("iteration %d: spellings of shelf A1", iteration).isEqualTo(1);
			jdbcTemplate.update("DELETE FROM part_locations");
			jdbcTemplate.update("DELETE FROM parts");
		}
	}

	/**
	 * Two parts are created at once, each on a shelf no part holds yet, typed
	 * "n9" and "N9". The second creation waits on the shelf lock and then
	 * takes the first one's committed spelling.
	 */
	@Test
	void twoCreatesIntroducingANewShelfInDifferentCaseStoreOneSpelling() throws Exception {
		MockHttpSession manager = sessionFor(MANAGER_EMAIL, Role.MANAGER);

		for (int iteration = 0; iteration < ITERATIONS; iteration++) {
			int current = iteration;
			long secondDelayMillis = iteration % 2 == 1 ? 100 : 0;

			MvcResult[] results = race(() -> mockMvc.perform(post("/parts").session(manager)
				.with(csrf())
				.param("name", "New Shelf P " + current)
				.param("quantity", "1")
				.param("locations", "n9")).andReturn(), () -> {
					Thread.sleep(secondDelayMillis);
					return mockMvc.perform(post("/parts").session(manager)
						.with(csrf())
						.param("name", "New Shelf Q " + current)
						.param("quantity", "1")
						.param("locations", "N9")).andReturn();
				});

			assertRedirected(results[0], "first create", iteration);
			assertRedirected(results[1], "second create", iteration);
			assertThat(spellingsOf("n9")).as("iteration %d: spellings of shelf N9", iteration).isEqualTo(1);
			jdbcTemplate.update("DELETE FROM part_locations");
			jdbcTemplate.update("DELETE FROM parts");
		}
	}

}

package pl.regavio.stockahead.parts;

import java.time.Instant;
import java.util.List;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Covers the Phase 4 contract: the DB-level quantity guard, the
 * manager/technician role split on every parts route, and every create/edit
 * business rule (uniqueness, location parsing/dedup, reconciliation,
 * rollback-on-conflict) against a real Postgres via Testcontainers, per
 * AGENTS.md. Deliberately not {@code @Transactional}: each MockMvc request
 * must commit its own application transaction so the controller's own
 * {@code TransactionTemplate} and the DB constraint are genuinely exercised.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PartsCatalogIntegrationTests {

	private static final String MANAGER_EMAIL = "catalog-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "catalog-technician@example.com";

	private static final String PASSWORD = "correct-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PartRepository partRepository;

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
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
	}

	// ---- fixtures -----------------------------------------------------

	private void seedManager() {
		seedAccount(MANAGER_EMAIL, PASSWORD, Role.MANAGER, true);
	}

	private void seedTechnician() {
		seedAccount(TECHNICIAN_EMAIL, PASSWORD, Role.TECHNICIAN, true);
	}

	private void seedAccount(String email, String rawPassword, Role role, boolean active) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(rawPassword));
			account.setRole(role);
			account.setActive(active);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/**
	 * Persists a part the same way {@code PartController.create()} does:
	 * inside a {@code TransactionTemplate}, via {@code addLocation} then
	 * {@code saveAndFlush}. Returns the generated id.
	 */
	private Long seedPart(String name, int quantity, boolean active, String... locations) {
		Part part = new Part();
		part.setName(name);
		part.setQuantity(quantity);
		part.setActive(active);
		part.setCreatedAt(Instant.now());
		for (String location : locations) {
			PartLocation partLocation = new PartLocation();
			partLocation.setLocation(location);
			part.addLocation(partLocation);
		}
		transactionTemplate.executeWithoutResult(status -> partRepository.saveAndFlush(part));
		return part.getId();
	}

	/**
	 * {@code Part.locations} is lazy, and the entity returned by a direct
	 * repository call is detached once that call's own transaction closes —
	 * these helpers re-fetch inside a fresh read transaction and extract only
	 * the plain values assertions need, per the class-level discipline of
	 * never asserting against a cached/detached entity.
	 */
	private List<Long> locationIdsOf(Long partId) {
		return transactionTemplate.execute(status -> partRepository.findById(partId).orElseThrow()
			.getLocations().stream().map(PartLocation::getId).toList());
	}

	private List<String> locationValuesOf(Long partId) {
		return transactionTemplate.execute(status -> partRepository.findById(partId).orElseThrow()
			.getLocations().stream().map(PartLocation::getLocation).toList());
	}

	private Long locationIdOf(Long partId, String location) {
		return transactionTemplate.execute(status -> partRepository.findById(partId).orElseThrow()
			.getLocations().stream()
			.filter(existing -> existing.getLocation().equals(location))
			.findFirst().orElseThrow().getId());
	}

	// ---- DB constraint --------------------------------------------------

	@Test
	void negativeQuantityViolatesDatabaseCheckConstraint() {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
				"INSERT INTO parts (name, quantity, active, created_at) VALUES (?, ?, ?, now())",
				"Negative Stock Part", -1, true)))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(partRepository.findByName("Negative Stock Part")).isEmpty();
	}

	// ---- create -----------------------------------------------------------

	@Test
	void managerCreatesPartWithValidNameQuantityAndLocations() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/parts").session(session).with(csrf())
				.param("name", "Resistor 10k")
				.param("quantity", "42")
				.param("locations", "A1\nB2"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/parts"));

		transactionTemplate.executeWithoutResult(status -> {
			Part created = partRepository.findByName("Resistor 10k").orElseThrow();
			assertThat(created.getQuantity()).isEqualTo(42);
			assertThat(created.isActive()).isTrue();
			assertThat(created.getLocations()).extracting(PartLocation::getLocation)
				.containsExactlyInAnyOrder("A1", "B2");
		});
	}

	/**
	 * The controller pre-checks name uniqueness via {@code findByName} before
	 * opening its write transaction, so a sequential duplicate-name POST only
	 * ever exercises that pre-check. To genuinely exercise the DB unique
	 * constraint and the controller's
	 * {@code catch (DataIntegrityViolationException)} race-handling branch
	 * (see the "(race)" comment in {@code PartController.create()}), this
	 * test holds a same-named row open-but-uncommitted in a manually managed
	 * transaction on a background thread while a concurrent request races to
	 * create the same name: the racer's pre-check sees nothing (the holder
	 * hasn't committed), so it proceeds to its own INSERT, which blocks on
	 * Postgres's unique-index conflict resolution until the holder commits —
	 * at which point the racer's INSERT fails with a real unique violation
	 * that the controller must translate into the friendly re-render. The
	 * externally observable assertions (200, friendly message, exactly one
	 * committed row) hold regardless of the exact interleaving, so this test
	 * cannot flake into a false failure even if the race window is missed.
	 */
	@Test
	void duplicatePartNameOnCreateHitsDatabaseConstraintAndRendersFriendlyErrorWithNoDuplicateRow() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		CountDownLatch uncommittedRowFlushed = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Thread holder = new Thread(() -> {
			TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
			try {
				Part part = new Part();
				part.setName("Race Condition Part");
				part.setQuantity(1);
				part.setActive(true);
				part.setCreatedAt(Instant.now());
				PartLocation location = new PartLocation();
				location.setLocation("Holder Shelf");
				part.addLocation(location);
				partRepository.saveAndFlush(part);
				uncommittedRowFlushed.countDown();
				// Give the racing request time to pass its own pre-check
				// (which cannot see this uncommitted row) and reach its own
				// INSERT, which then blocks against this open transaction.
				Thread.sleep(500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				transactionManager.commit(status);
			}
		}, "duplicate-name-race-holder");
		holder.setDaemon(true);

		try {
			holder.start();
			assertThat(uncommittedRowFlushed.await(5, TimeUnit.SECONDS)).isTrue();

			Future<MvcResult> racer = executor.submit(() -> mockMvc
				.perform(post("/parts").session(session).with(csrf())
					.param("name", "Race Condition Part")
					.param("quantity", "2")
					.param("locations", "Racer Shelf"))
				.andReturn());

			MvcResult result = racer.get(15, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(result.getResponse().getContentAsString())
				.contains("Część o tej nazwie już istnieje.");
		}
		finally {
			holder.join(10_000);
			executor.shutdownNow();
		}

		transactionTemplate.executeWithoutResult(status -> {
			List<Part> matches = partRepository.findAll().stream()
				.filter(part -> part.getName().equals("Race Condition Part"))
				.toList();
			assertThat(matches).hasSize(1);
			assertThat(matches.get(0).getQuantity()).isEqualTo(1);
			assertThat(matches.get(0).getLocations()).extracting(PartLocation::getLocation)
				.containsExactly("Holder Shelf");
		});
	}

	@Test
	void createWithZeroLocationsFailsValidationAndCreatesNoRow() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/parts").session(session).with(csrf())
				.param("name", "Diode 1N4148")
				.param("quantity", "10")
				.param("locations", "   \n  "))
			.andExpect(status().isOk());

		assertThat(partRepository.findByName("Diode 1N4148")).isEmpty();
	}

	@Test
	void createWithDuplicateLocationLinesFailsValidationAndCreatesNoRow() throws Exception {
		seedManager();
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/parts").session(session).with(csrf())
				.param("name", "Transistor BC547")
				.param("quantity", "10")
				.param("locations", "A1\nA1"))
			.andExpect(status().isOk());

		assertThat(partRepository.findByName("Transistor BC547")).isEmpty();
	}

	// ---- role gating --------------------------------------------------

	@Test
	void technicianGets403OnEveryManagerOnlyPartsRoute() throws Exception {
		seedManager();
		seedTechnician();
		Long partId = seedPart("Fuse 5A", 3, true, "C1");
		MockHttpSession session = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts/new").session(session))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/parts").session(session).with(csrf())
				.param("name", "Should Not Be Created")
				.param("quantity", "1")
				.param("locations", "A1"))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/parts/" + partId + "/edit").session(session))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/parts/" + partId).session(session).with(csrf())
				.param("name", "Fuse 5A Renamed")
				.param("locations", "C1"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/parts/" + partId + "/deactivate").session(session).with(csrf()))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/parts/" + partId + "/reactivate").session(session).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void bothRolesGet200OnPartsList() throws Exception {
		seedManager();
		seedTechnician();

		mockMvc.perform(get("/parts").session(loginAs(MANAGER_EMAIL)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/parts").session(loginAs(TECHNICIAN_EMAIL)))
			.andExpect(status().isOk());
	}

	// ---- search -----------------------------------------------------------

	@Test
	void searchMatchesByNameSubstringCaseInsensitive() throws Exception {
		seedManager();
		seedPart("Electrolytic Capacitor 220uF", 5, true, "Shelf 1");
		seedPart("Resistor 10k", 5, true, "Shelf 2");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts").session(session).param("q", "capacitor"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Electrolytic Capacitor 220uF")))
			.andExpect(content().string(not(containsString("Resistor 10k"))));
	}

	@Test
	void searchMatchesByLocationSubstring() throws Exception {
		seedManager();
		seedPart("Electrolytic Capacitor 220uF", 5, true, "Warehouse-Bay-7");
		seedPart("Resistor 10k", 5, true, "Warehouse-Bay-9");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts").session(session).param("q", "bay-7"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Electrolytic Capacitor 220uF")))
			.andExpect(content().string(not(containsString("Resistor 10k"))));
	}

	// ---- active / inactive visibility --------------------------------------

	@Test
	void deactivatedPartExcludedByDefaultButShownWithShowInactiveForManager() throws Exception {
		seedManager();
		seedTechnician();
		seedPart("Active Widget", 5, true, "A1");
		seedPart("Retired Widget", 0, false, "B1");

		MockHttpSession managerSession = loginAs(MANAGER_EMAIL);

		mockMvc.perform(get("/parts").session(managerSession))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Widget")))
			.andExpect(content().string(not(containsString("Retired Widget"))));

		mockMvc.perform(get("/parts").session(managerSession).param("showInactive", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Widget")))
			.andExpect(content().string(containsString("Retired Widget")));

		MockHttpSession technicianSession = loginAs(TECHNICIAN_EMAIL);

		mockMvc.perform(get("/parts").session(technicianSession).param("showInactive", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Active Widget")))
			.andExpect(content().string(not(containsString("Retired Widget"))));
	}

	@Test
	void reactivatingPartMakesItReappearInDefaultList() throws Exception {
		seedManager();
		Long partId = seedPart("Mothballed Sensor", 0, false, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/parts/" + partId + "/reactivate").session(session).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/parts?showInactive=true"));

		Part reactivated = partRepository.findById(partId).orElseThrow();
		assertThat(reactivated.isActive()).isTrue();

		mockMvc.perform(get("/parts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Mothballed Sensor")));
	}

	// ---- edit ---------------------------------------------------------

	@Test
	void editNeverChangesQuantityEvenWhenQuantityParamIsInjected() throws Exception {
		seedManager();
		Long partId = seedPart("Adjustable Widget", 7, true, "A1");
		MockHttpSession session = loginAs(MANAGER_EMAIL);

		mockMvc.perform(post("/parts/" + partId).session(session).with(csrf())
				.param("name", "Adjustable Widget")
				.param("quantity", "999")
				.param("locations", "A1"))
			.andExpect(status().is3xxRedirection());

		Part reloaded = partRepository.findById(partId).orElseThrow();
		assertThat(reloaded.getQuantity()).isEqualTo(7);
	}

	@Test
	void editWithUnchangedLocationsPreservesRowIds() throws Exception {
		seedManager();
		Long partId = seedPart("Stable Widget", 3, true, "A1", "B2");
		Long idA = locationIdOf(partId, "A1");
		Long idB = locationIdOf(partId, "B2");

		MockHttpSession session = loginAs(MANAGER_EMAIL);
		mockMvc.perform(post("/parts/" + partId).session(session).with(csrf())
				.param("name", "Stable Widget")
				.param("locations", "A1\nB2"))
			.andExpect(status().is3xxRedirection());

		assertThat(locationIdsOf(partId)).hasSize(2);
		assertThat(locationIdOf(partId, "A1")).isEqualTo(idA);
		assertThat(locationIdOf(partId, "B2")).isEqualTo(idB);
	}

	@Test
	void editFromAbToBcRetainsBDeletesAAndCreatesCOnce() throws Exception {
		seedManager();
		Long partId = seedPart("Reshuffled Widget", 3, true, "A", "B");
		Long idA = locationIdOf(partId, "A");
		Long idB = locationIdOf(partId, "B");

		MockHttpSession session = loginAs(MANAGER_EMAIL);
		mockMvc.perform(post("/parts/" + partId).session(session).with(csrf())
				.param("name", "Reshuffled Widget")
				.param("locations", "B\nC"))
			.andExpect(status().is3xxRedirection());

		List<Long> idsAfter = locationIdsOf(partId);
		assertThat(idsAfter).hasSize(2);
		assertThat(locationValuesOf(partId)).containsExactlyInAnyOrder("B", "C");
		assertThat(locationIdOf(partId, "B")).isEqualTo(idB);
		assertThat(idsAfter).doesNotContain(idA);
	}

	@Test
	void editToAnotherPartsExistingNameRollsBackAndPreservesOriginalState() throws Exception {
		seedManager();
		Long firstId = seedPart("Part One", 4, true, "A1", "B1");
		seedPart("Part Two", 9, true, "Z9");

		List<Long> locationIdsBefore = locationIdsOf(firstId);
		List<String> locationValuesBefore = locationValuesOf(firstId);

		MockHttpSession session = loginAs(MANAGER_EMAIL);
		mockMvc.perform(post("/parts/" + firstId).session(session).with(csrf())
				.param("name", "Part Two")
				.param("locations", "C1\nD1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Część o tej nazwie już istnieje.")));

		transactionTemplate.executeWithoutResult(status -> {
			Part firstAfter = partRepository.findById(firstId).orElseThrow();
			assertThat(firstAfter.getName()).isEqualTo("Part One");
			assertThat(firstAfter.getQuantity()).isEqualTo(4);
			assertThat(firstAfter.isActive()).isTrue();
			assertThat(firstAfter.getLocations().stream().map(PartLocation::getId).toList())
				.containsExactlyInAnyOrderElementsOf(locationIdsBefore);
			assertThat(firstAfter.getLocations().stream().map(PartLocation::getLocation).toList())
				.containsExactlyInAnyOrderElementsOf(locationValuesBefore);
		});
	}

}

package pl.regavio.stockahead.parts;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The company guardrail for stock movements: company B's users get 404 on
 * company A's part through every stock-correction route (also with invalid
 * input) and change nothing; a delivery naming A's part is the
 * {@code partNotFound} error and changes neither company's stock; B's
 * delivery never takes A's shelf spelling; a CSV import naming a part that
 * exists only in A creates a new part in B; A's own correction and delivery
 * still reallocate A's short order; and B's foreign-id attempts never wait
 * on A's held part-row lock. Against real Postgres via Testcontainers,
 * through the real HTTP routes and form login, so the principal carries its
 * company.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class StockMovementsCompanyIsolationIntegrationTests {

	private static final String MANAGER_A_EMAIL = "manager-a@stock-movements-isolation.example";

	private static final String MANAGER_B_EMAIL = "manager-b@stock-movements-isolation.example";

	private static final String TECHNICIAN_B_EMAIL = "technician-b@stock-movements-isolation.example";

	private static final String PASSWORD = "correct-password";

	private static final String PART_NAME = "R1";

	private static final String SHELF_A = "Regał X";

	private static final String PART_NOT_FOUND = "Jedna z wybranych części nie istnieje.";

	private static final String CSV_HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CompanyFixtures companyFixtures;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PartRepository partRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Company companyA;

	private Company companyB;

	private Part partA;

	private Part partB;

	private Project projectA;

	/** A's open, untaken order line on A's part: 8 required, 5 reserved (all of A's stock), so 3 short. */
	private Long lineA;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyA = companyFixtures.company("StockMovementsCompanyIsolationIntegrationTests A");
		companyB = companyFixtures.company("StockMovementsCompanyIsolationIntegrationTests B");
		companyFixtures.account(companyA, MANAGER_A_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		companyFixtures.account(companyB, TECHNICIAN_B_EMAIL, PASSWORD, Role.TECHNICIAN, true);
		partA = companyFixtures.part(companyA, PART_NAME, 5, SHELF_A);
		partB = companyFixtures.part(companyB, PART_NAME, 5, "B1");
		projectA = companyFixtures.project(companyA, "Board X", true);
		companyFixtures.bomLine(projectA, partA, 1);
		lineA = seedShortOrderLineA();
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email LIKE '%@stock-movements-isolation.example'");
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private Long seedShortOrderLineA() {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, 8, 'NORMAL', ?, ?) RETURNING id",
				Long.class, companyA.getId(), projectA.getId(), LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()));
		return jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, 8, 5) RETURNING id",
				Long.class, companyA.getId(), orderId, partA.getId());
	}

	private int quantityOf(Part part) {
		return jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part.getId());
	}

	private List<String> locationsOf(Part part) {
		return jdbcTemplate.queryForList("SELECT location FROM part_locations WHERE part_id = ? ORDER BY id",
				String.class, part.getId());
	}

	private int reservedOfLineA() {
		return jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class,
				lineA);
	}

	private int correctionCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM stock_corrections", Integer.class);
	}

	/** Everything a stock movement could change: both parts' stock and locations, reservations, corrections. */
	private String snapshot() {
		return quantityOf(partA) + " " + locationsOf(partA) + " / " + quantityOf(partB) + " " + locationsOf(partB)
				+ " / " + jdbcTemplate.queryForList(
						"SELECT id || ':' || reserved_quantity || '/' || picked_quantity FROM order_lines ORDER BY id",
						String.class)
				+ " / " + correctionCount();
	}

	private MockHttpServletRequestBuilder setTotal(MockHttpSession session, Long partId, String newQuantity,
			String expectedQuantity) {
		return post("/parts/{id}/correction/set", partId).session(session)
			.with(csrf())
			.param("newQuantity", newQuantity)
			.param("expectedQuantity", expectedQuantity)
			.param("reason", "Inwentaryzacja");
	}

	private MockHttpServletRequestBuilder adjustBy(MockHttpSession session, Long partId, String delta) {
		return post("/parts/{id}/correction/adjust", partId).session(session)
			.with(csrf())
			.param("delta", delta)
			.param("reason", "Inwentaryzacja");
	}

	private MockHttpServletRequestBuilder delivery(MockHttpSession session, String... nameValuePairs) {
		MockHttpServletRequestBuilder request = post("/deliveries").session(session).with(csrf()).param("rows", "5");
		for (int i = 0; i < nameValuePairs.length; i += 2) {
			request.param(nameValuePairs[i], nameValuePairs[i + 1]);
		}
		return request;
	}

	/** Performs a delivery that must be rejected with {@code partNotFound} and asserts nothing changed. */
	private void assertDeliveryPartNotFound(MockHttpServletRequestBuilder request) throws Exception {
		String before = snapshot();
		String html = mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name(DeliveryController.VIEW))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).contains(PART_NOT_FOUND);
		assertThat(snapshot()).isEqualTo(before);
	}

	// ---- stock correction -----------------------------------------------

	@Test
	void otherCompanysManagerGets404OnCorrectionPage() throws Exception {
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);

		mockMvc.perform(get("/parts/{id}/correction", partA.getId()).session(sessionB))
			.andExpect(status().isNotFound());
	}

	@Test
	void otherCompanysManagerGets404OnSetTotalAndChangesNothing() throws Exception {
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		String before = snapshot();

		mockMvc.perform(setTotal(sessionB, partA.getId(), "2", Integer.toString(quantityOf(partA))))
			.andExpect(status().isNotFound());

		assertThat(snapshot()).isEqualTo(before);
		assertThat(quantityOf(partA)).isEqualTo(5);
		assertThat(correctionCount()).isZero();
		assertThat(reservedOfLineA()).isEqualTo(5);
	}

	@Test
	void otherCompanysManagerGets404OnAdjustAndChangesNothing() throws Exception {
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		String before = snapshot();

		mockMvc.perform(adjustBy(sessionB, partA.getId(), "-3")).andExpect(status().isNotFound());

		assertThat(snapshot()).isEqualTo(before);
		assertThat(correctionCount()).isZero();
	}

	@Test
	void otherCompanysManagerGets404OnInvalidCorrectionInputNotARenderedPage() throws Exception {
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		String before = snapshot();

		mockMvc.perform(setTotal(sessionB, partA.getId(), "not-a-number", "5")).andExpect(status().isNotFound());
		mockMvc.perform(adjustBy(sessionB, partA.getId(), "0")).andExpect(status().isNotFound());

		assertThat(snapshot()).isEqualTo(before);
	}

	// ---- delivery receipt -----------------------------------------------

	@Test
	void otherCompanysTechnicianDeliveringForeignPartGetsPartNotFound() throws Exception {
		MockHttpSession sessionB = loginAs(TECHNICIAN_B_EMAIL);

		assertDeliveryPartNotFound(delivery(sessionB,
				"partId0", partA.getId().toString(), "quantity0", "10", "location0", "Regał N1"));

		assertThat(quantityOf(partA)).isEqualTo(5);
		assertThat(locationsOf(partA)).containsExactly(SHELF_A);
		assertThat(reservedOfLineA()).isEqualTo(5);
	}

	@Test
	void deliveryMixingOwnAndForeignPartChangesNeither() throws Exception {
		MockHttpSession sessionB = loginAs(TECHNICIAN_B_EMAIL);

		assertDeliveryPartNotFound(delivery(sessionB,
				"partId0", partB.getId().toString(), "quantity0", "4", "location0", "B2",
				"partId1", partA.getId().toString(), "quantity1", "6"));

		assertThat(quantityOf(partA)).isEqualTo(5);
		assertThat(quantityOf(partB)).isEqualTo(5);
		assertThat(locationsOf(partB)).containsExactly("B1");
	}

	@Test
	void deliveryToShelfSpelledLikeAnotherCompanysKeepsOwnSpelling() throws Exception {
		MockHttpSession sessionB = loginAs(TECHNICIAN_B_EMAIL);

		mockMvc.perform(delivery(sessionB,
				"partId0", partB.getId().toString(), "quantity0", "2", "location0", "regał x"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"));

		assertThat(quantityOf(partB)).isEqualTo(7);
		assertThat(locationsOf(partB)).containsExactly("B1", "regał x");
		assertThat(locationsOf(partA)).containsExactly(SHELF_A);
	}

	// ---- CSV import -----------------------------------------------------

	@Test
	void importNamingPartOnlyInOtherCompanyCreatesNewPartInOwnCompany() throws Exception {
		Part onlyInA = companyFixtures.part(companyA, "C7", 9, "A2");
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		MockMultipartFile file = new MockMultipartFile("file", "czesci.csv", "text/csv",
				(CSV_HEADER + "C7;4;B9\r\n").getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/parts/import").file(file).session(sessionB).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("parts-import-preview"));
		PendingImport pending = (PendingImport) sessionB.getAttribute(PendingImport.SESSION_ATTRIBUTE);
		assertThat(pending).isNotNull();
		mockMvc.perform(post("/parts/import/confirm").session(sessionB).with(csrf()).param("token", pending.token()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"));

		Long newPartId = jdbcTemplate.queryForObject("SELECT id FROM parts WHERE company_id = ? AND name = 'C7'",
				Long.class, companyB.getId());
		assertThat(newPartId).isNotEqualTo(onlyInA.getId());
		assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, newPartId))
			.isEqualTo(4);
		assertThat(quantityOf(onlyInA)).isEqualTo(9);
		assertThat(locationsOf(onlyInA)).containsExactly("A2");
	}

	// ---- own company still works ------------------------------------------

	@Test
	void ownCompanyCorrectionAndDeliveryStillReallocateShortOrder() throws Exception {
		MockHttpSession sessionA = loginAs(MANAGER_A_EMAIL);

		mockMvc.perform(get("/parts/{id}/correction", partA.getId()).session(sessionA)).andExpect(status().isOk());
		mockMvc.perform(adjustBy(sessionA, partA.getId(), "2"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"));

		assertThat(quantityOf(partA)).isEqualTo(7);
		assertThat(reservedOfLineA()).isEqualTo(7);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_corrections WHERE company_id = ?",
				Integer.class, companyA.getId())).isEqualTo(1);

		mockMvc.perform(delivery(sessionA, "partId0", partA.getId().toString(), "quantity0", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/parts"));

		assertThat(quantityOf(partA)).isEqualTo(8);
		assertThat(reservedOfLineA()).isEqualTo(8);
		assertThat(quantityOf(partB)).isEqualTo(5);
	}

	// ---- no cross-company lock waits ----------------------------------------

	@Test
	void foreignIdDeliveryAndCorrectionNeverWaitOnOtherCompanysPartLock() throws Exception {
		MockHttpSession technicianB = loginAs(TECHNICIAN_B_EMAIL);
		MockHttpSession managerB = loginAs(MANAGER_B_EMAIL);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		try {
			Future<Void> holder = executor.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					partRepository.findByIdInAndCompanyIdForUpdate(Set.of(partA.getId()), companyA.getId());
					locked.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				});
				return null;
			});
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			Future<String> deliveryB = executor.submit(() -> mockMvc
				.perform(delivery(technicianB, "partId0", partA.getId().toString(), "quantity0", "10"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
			assertThat(deliveryB.get(15, TimeUnit.SECONDS)).contains(PART_NOT_FOUND);

			Future<Void> correctionB = executor.submit(() -> {
				mockMvc.perform(adjustBy(managerB, partA.getId(), "1")).andExpect(status().isNotFound());
				return null;
			});
			correctionB.get(15, TimeUnit.SECONDS);

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(quantityOf(partA)).isEqualTo(5);
		assertThat(reservedOfLineA()).isEqualTo(5);
		assertThat(correctionCount()).isZero();
	}

}

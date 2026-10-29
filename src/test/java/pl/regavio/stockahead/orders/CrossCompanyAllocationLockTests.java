package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;
import pl.regavio.stockahead.projects.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that allocation is per company under real row locks on real
 * Postgres: while one transaction holds company A's part rows
 * ({@code PESSIMISTIC_WRITE}), company B's order creation and B's
 * reallocation — even one handed A's part id — complete within a bounded
 * wait instead of queueing behind A's lock, and a foreign part id passed to
 * {@link ReservationAllocator#reallocateForParts(Long, java.util.Set)} is
 * neither read nor allocated. Fails if a future change re-globalizes the
 * allocator's lock or reads. Uses the latch-and-executor pattern of
 * {@link ReservationConcurrencyTests}.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class CrossCompanyAllocationLockTests {

	private static final String MANAGER_B_EMAIL = "manager-b@cross-company-lock.example";

	private static final String PASSWORD = "correct-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReservationAllocator reservationAllocator;

	@Autowired
	private PartRepository partRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company companyA;

	private Company companyB;

	private Part partA;

	private Part partB;

	private Project projectA;

	private Project projectB;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyA = companyFixtures.company("CrossCompanyAllocationLockTests A");
		companyB = companyFixtures.company("CrossCompanyAllocationLockTests B");
		companyFixtures.account(companyB, MANAGER_B_EMAIL, PASSWORD, Role.MANAGER, true);
		partA = companyFixtures.part(companyA, "R1", 5, "A1");
		partB = companyFixtures.part(companyB, "R1", 5, "B1");
		projectA = companyFixtures.project(companyA, "Board X", true);
		projectB = companyFixtures.project(companyB, "Board X", true);
		companyFixtures.bomLine(projectA, partA, 1);
		companyFixtures.bomLine(projectB, partB, 1);
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
		companyFixtures.cleanUp();
	}

	// ---- fixtures -----------------------------------------------------

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	/** An untaken {@code OPEN} order of A on A's "R1" requiring {@code required}, reserving {@code reserved}. */
	private Long seedOrderLineA(int required, int reserved) {
		Long orderId = jdbcTemplate.queryForObject(
				"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, created_at) "
						+ "VALUES (?, ?, ?, 'NORMAL', ?, ?) RETURNING id",
				Long.class, companyA.getId(), projectA.getId(), required, LocalDate.now().plusDays(7),
				Timestamp.from(Instant.now()));
		return jdbcTemplate.queryForObject(
				"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, companyA.getId(), orderId, partA.getId(), required, reserved);
	}

	private int reservedOfLine(Long lineId) {
		Integer reserved = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?",
				Integer.class, lineId);
		return reserved == null ? 0 : reserved;
	}

	private int reservedOfCompany(Company company) {
		Integer reserved = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_lines WHERE company_id = ?", Integer.class,
				company.getId());
		return reserved == null ? 0 : reserved;
	}

	private void reallocateB(Set<Long> partIds) {
		new TransactionTemplate(transactionManager).executeWithoutResult(
				status -> reservationAllocator.reallocateForParts(companyB.getId(), partIds));
	}

	// ---- tests --------------------------------------------------------

	@Test
	void companyBsOrderCreationAndReallocationNeverWaitOnCompanyAsPartLocks() throws Exception {
		Long lineA = seedOrderLineA(3, 3);
		MockHttpSession sessionB = loginAs(MANAGER_B_EMAIL);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		try {
			Future<Void> holder = executor.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					partRepository.findByIdInForUpdate(Set.of(partA.getId()));
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

			Future<Void> createB = executor.submit(() -> {
				mockMvc.perform(post("/orders").session(sessionB).with(csrf())
						.param("projectId", projectB.getId().toString())
						.param("quantityUnits", "2")
						.param("priority", "NORMAL")
						.param("requiredDate", LocalDate.now().plusDays(1).toString()))
					.andExpect(status().is3xxRedirection());
				return null;
			});
			createB.get(5, TimeUnit.SECONDS);

			Future<Void> reallocateB = executor.submit(() -> {
				reallocateB(Set.of(partA.getId(), partB.getId()));
				return null;
			});
			reallocateB.get(5, TimeUnit.SECONDS);

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}

		assertThat(reservedOfLine(lineA)).isEqualTo(3);
		assertThat(reservedOfCompany(companyA)).isEqualTo(3);
		assertThat(reservedOfCompany(companyB)).isEqualTo(2);
	}

	@Test
	void foreignPartIdPassedToReallocationIsNeitherReadNorAllocated() {
		jdbcTemplate.update("UPDATE parts SET quantity = 2 WHERE id = ?", partA.getId());
		Long lineA = seedOrderLineA(5, 2);
		// Raise A's stock without reallocating: A's line stays short until A's next event.
		jdbcTemplate.update("UPDATE parts SET quantity = 5 WHERE id = ?", partA.getId());

		reallocateB(Set.of(partA.getId(), partB.getId()));

		assertThat(reservedOfLine(lineA)).isEqualTo(2);
	}

}

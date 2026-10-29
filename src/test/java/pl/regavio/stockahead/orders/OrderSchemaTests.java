package pl.regavio.stockahead.orders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V17 company constraints on orders and their lines hold at the
 * PostgreSQL level, independently of any controller check: an order never
 * links its company to another company's project or assignee, and a line
 * never links its order's company to another company's part or order.
 * Deliberately not {@code @Transactional}: every {@code JdbcTemplate} call
 * runs as its own auto-committed statement so the constraint fires for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
class OrderSchemaTests {

	private static final String ASSIGNEE_A_EMAIL = "order-schema-assignee-a@example.com";

	private static final String ASSIGNEE_B_EMAIL = "order-schema-assignee-b@example.com";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Long companyAId;

	private Long companyBId;

	private Long assigneeAId;

	private Long assigneeBId;

	@BeforeEach
	void setUp() {
		cleanUp();
		Company companyA = companyFixtures.company("OrderSchemaTests A");
		Company companyB = companyFixtures.company("OrderSchemaTests B");
		companyAId = companyA.getId();
		companyBId = companyB.getId();
		assigneeAId = companyFixtures.account(companyA, ASSIGNEE_A_EMAIL, "secret-password", Role.TECHNICIAN, true)
			.getId();
		assigneeBId = companyFixtures.account(companyB, ASSIGNEE_B_EMAIL, "secret-password", Role.TECHNICIAN, true)
			.getId();
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

	private long insertProject(Long ownerId, String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO projects (name, company_id) VALUES (?, ?) RETURNING id", Long.class, name, ownerId);
	}

	private long insertPart(Long ownerId, String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO parts (name, quantity, company_id) VALUES (?, 10, ?) RETURNING id", Long.class, name,
			ownerId);
	}

	private long insertOrder(Long orderCompanyId, long projectId, Long assigneeId) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date, assignee_id) "
					+ "VALUES (?, ?, 1, 'NORMAL', CURRENT_DATE, ?) RETURNING id",
			Long.class, orderCompanyId, projectId, assigneeId);
	}

	private void insertLine(Long lineCompanyId, long orderId, long partId) {
		jdbcTemplate.update(
			"INSERT INTO order_lines (company_id, order_id, part_id, required_quantity) VALUES (?, ?, ?, 1)",
			lineCompanyId, orderId, partId);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	@Test
	void orderOfOneCompanyOnAnotherCompanysProjectIsRejected() {
		long projectAId = insertProject(companyAId, "Board A");

		assertThatThrownBy(() -> insertOrder(companyBId, projectAId, null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("orders")).isZero();
	}

	@Test
	void orderAssignedToAnotherCompanysAccountIsRejected() {
		long projectAId = insertProject(companyAId, "Board A");

		assertThatThrownBy(() -> insertOrder(companyAId, projectAId, assigneeBId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("orders")).isZero();
	}

	@Test
	void unassignedOrderIsAccepted() {
		long projectAId = insertProject(companyAId, "Board A");

		insertOrder(companyAId, projectAId, null);

		assertThat(count("orders")).isEqualTo(1);
	}

	@Test
	void orderAssignedToOwnCompanysAccountIsAccepted() {
		long projectAId = insertProject(companyAId, "Board A");

		insertOrder(companyAId, projectAId, assigneeAId);

		assertThat(count("orders")).isEqualTo(1);
	}

	@Test
	void lineOfOneCompanysOrderOnAnotherCompanysPartIsRejected() {
		long projectAId = insertProject(companyAId, "Board A");
		long orderAId = insertOrder(companyAId, projectAId, null);
		long partBId = insertPart(companyBId, "Resistor 10k");

		assertThatThrownBy(() -> insertLine(companyAId, orderAId, partBId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertLine(companyBId, orderAId, partBId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("order_lines")).isZero();
	}

	@Test
	void lineWhoseCompanyDiffersFromItsOrdersIsRejected() {
		long projectAId = insertProject(companyAId, "Board A");
		long orderAId = insertOrder(companyAId, projectAId, null);
		long partAId = insertPart(companyAId, "Resistor 10k");

		assertThatThrownBy(() -> insertLine(companyBId, orderAId, partAId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("order_lines")).isZero();

		insertLine(companyAId, orderAId, partAId);
		assertThat(count("order_lines")).isEqualTo(1);
	}

}

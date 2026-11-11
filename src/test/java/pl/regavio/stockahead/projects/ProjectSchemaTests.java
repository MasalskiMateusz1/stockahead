package pl.regavio.stockahead.projects;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V5 constraints on projects, BOM lines and documentation links,
 * and the V16 company constraints (project names unique per company, a BOM
 * line never linking two companies), hold at the PostgreSQL level,
 * independently of any controller validation.
 * Deliberately not {@code @Transactional}: every {@code JdbcTemplate} call
 * runs as its own auto-committed statement so the constraint fires for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
class ProjectSchemaTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Long companyId;

	private Long otherCompanyId;

	@BeforeEach
	void setUp() {
		cleanUp();
		companyId = companyFixtures.company("ProjectSchemaTests Co").getId();
		otherCompanyId = companyFixtures.company("ProjectSchemaTests Other Co").getId();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		companyFixtures.cleanUp();
	}

	private long insertProject(String name) {
		return insertProject(companyId, name);
	}

	private long insertProject(Long ownerId, String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO projects (name, company_id) VALUES (?, ?) RETURNING id", Long.class, name, ownerId);
	}

	private long insertPart(String name) {
		return insertPart(companyId, name);
	}

	private long insertPart(Long ownerId, String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO parts (name, quantity, company_id) VALUES (?, 10, ?) RETURNING id", Long.class, name,
			ownerId);
	}

	private void insertBomLine(long projectId, long partId, int quantityPerUnit) {
		insertBomLine(projectId, partId, quantityPerUnit, companyId);
	}

	private void insertBomLine(long projectId, long partId, int quantityPerUnit, Long lineCompanyId) {
		jdbcTemplate.update(
			"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit, company_id) VALUES (?, ?, ?, ?)",
			projectId, partId, quantityPerUnit, lineCompanyId);
	}

	private void insertLink(long projectId, String url) {
		jdbcTemplate.update("INSERT INTO project_links (project_id, url) VALUES (?, ?)", projectId, url);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	@Test
	void zeroQuantityPerUnitIsRejected() {
		long projectId = insertProject("Board A");
		long partId = insertPart("Resistor 10k");

		assertThatThrownBy(() -> insertBomLine(projectId, partId, 0))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("bom_lines")).isZero();
	}

	@Test
	void negativeQuantityPerUnitIsRejected() {
		long projectId = insertProject("Board A");
		long partId = insertPart("Resistor 10k");

		assertThatThrownBy(() -> insertBomLine(projectId, partId, -1))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("bom_lines")).isZero();
	}

	@Test
	void secondLineForSamePartInSameProjectIsRejected() {
		long projectId = insertProject("Board A");
		long partId = insertPart("Resistor 10k");
		insertBomLine(projectId, partId, 2);

		assertThatThrownBy(() -> insertBomLine(projectId, partId, 3))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("bom_lines")).isEqualTo(1);
	}

	@Test
	void deletingPartReferencedByBomLineIsRejected() {
		long projectId = insertProject("Board A");
		long partId = insertPart("Resistor 10k");
		insertBomLine(projectId, partId, 2);

		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM parts WHERE id = ?", partId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("parts")).isEqualTo(1);
	}

	@Test
	void nonHttpUrlIsRejected() {
		long projectId = insertProject("Board A");

		assertThatThrownBy(() -> insertLink(projectId, "javascript:alert(1)"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("project_links")).isZero();
	}

	@Test
	void uppercaseHttpsUrlIsAccepted() {
		long projectId = insertProject("Board A");

		insertLink(projectId, "HTTPS://example.com");

		assertThat(count("project_links")).isEqualTo(1);
	}

	@Test
	void duplicateProjectNameIsRejected() {
		insertProject("Board A");

		assertThatThrownBy(() -> insertProject("Board A"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("projects")).isEqualTo(1);
	}

	@Test
	void twoCompaniesEachHoldAProjectWithTheSameNameButOneCompanyCannotHoldItTwice() {
		long first = insertProject(companyId, "Board X");
		long second = insertProject(otherCompanyId, "Board X");

		assertThat(first).isNotEqualTo(second);
		assertThatThrownBy(() -> insertProject(companyId, "Board X"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects WHERE name = ?", Integer.class,
				"Board X")).isEqualTo(2);
	}

	@Test
	void bomLineLinkingOneCompanysProjectToAnotherCompanysPartIsRejected() {
		long projectId = insertProject(companyId, "Board A");
		long foreignPartId = insertPart(otherCompanyId, "Resistor 10k");

		assertThatThrownBy(() -> insertBomLine(projectId, foreignPartId, 2, companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertBomLine(projectId, foreignPartId, 2, otherCompanyId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("bom_lines")).isZero();
	}

}

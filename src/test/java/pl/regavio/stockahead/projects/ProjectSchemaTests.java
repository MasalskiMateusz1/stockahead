package pl.regavio.stockahead.projects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V5 constraints on projects, BOM lines and documentation links
 * hold at the PostgreSQL level, independently of any controller validation.
 * Deliberately not {@code @Transactional}: every {@code JdbcTemplate} call
 * runs as its own auto-committed statement so the constraint fires for real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProjectSchemaTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		cleanUp();
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
	}

	private long insertProject(String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO projects (name) VALUES (?) RETURNING id", Long.class, name);
	}

	private long insertPart(String name) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO parts (name, quantity) VALUES (?, 10) RETURNING id", Long.class, name);
	}

	private void insertBomLine(long projectId, long partId, int quantityPerUnit) {
		jdbcTemplate.update(
			"INSERT INTO bom_lines (project_id, part_id, quantity_per_unit) VALUES (?, ?, ?)",
			projectId, partId, quantityPerUnit);
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

}

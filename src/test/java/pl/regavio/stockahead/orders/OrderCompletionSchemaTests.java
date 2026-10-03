package pl.regavio.stockahead.orders;

import java.sql.Timestamp;
import java.time.Instant;

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
 * Proves the V8 and V12 completion constraints on {@code orders} hold at the
 * PostgreSQL level, independently of any controller check: a report needs a
 * taken order, the report timestamp and reporter are set or cleared
 * together, {@code COMPLETED} needs both a completion moment and a report,
 * and a built-units count lies in 0…{@code quantity_units} and exists only
 * with a report. Deliberately not {@code @Transactional}: every
 * {@code JdbcTemplate} call runs as its own auto-committed statement so the
 * constraint fires for real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderCompletionSchemaTests {

	private static final String REPORTER_EMAIL = "completion-schema-reporter@example.com";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private long reporterId;

	private long projectId;

	@BeforeEach
	void setUp() {
		cleanUp();
		reporterId = jdbcTemplate.queryForObject(
			"INSERT INTO accounts (email, password_hash, role) VALUES (?, 'x', 'TECHNICIAN') RETURNING id",
			Long.class, REPORTER_EMAIL);
		projectId = jdbcTemplate.queryForObject(
			"INSERT INTO projects (name) VALUES ('Completion Schema Board') RETURNING id", Long.class);
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
		jdbcTemplate.update("DELETE FROM accounts WHERE email = ?", REPORTER_EMAIL);
	}

	private long insertOrder(Instant takenAt) {
		return insertOrder(takenAt, 1);
	}

	private long insertOrder(Instant takenAt, int quantityUnits) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO orders (project_id, quantity_units, priority, required_date, taken_at) "
					+ "VALUES (?, ?, 'NORMAL', CURRENT_DATE, ?) RETURNING id",
			Long.class, projectId, quantityUnits, takenAt == null ? null : Timestamp.from(takenAt));
	}

	private long insertReportedOrder(int quantityUnits) {
		long orderId = insertOrder(Instant.now(), quantityUnits);
		jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now(), completion_reported_by = ? WHERE id = ?",
			reporterId, orderId);
		return orderId;
	}

	private Integer builtUnitsOf(long orderId) {
		return jdbcTemplate.queryForObject("SELECT built_units FROM orders WHERE id = ?", Integer.class, orderId);
	}

	private Timestamp completionReportedAtOf(long orderId) {
		return jdbcTemplate.queryForObject("SELECT completion_reported_at FROM orders WHERE id = ?",
			Timestamp.class, orderId);
	}

	private Long completionReportedByOf(long orderId) {
		return jdbcTemplate.queryForObject("SELECT completion_reported_by FROM orders WHERE id = ?", Long.class,
			orderId);
	}

	private String statusOf(long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	@Test
	void reportOnUntakenOrderIsRejected() {
		long orderId = insertOrder(null);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now(), completion_reported_by = ? WHERE id = ?",
			reporterId, orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(completionReportedAtOf(orderId)).isNull();
		assertThat(completionReportedByOf(orderId)).isNull();
	}

	@Test
	void reporterWithoutTimestampIsRejected() {
		long orderId = insertOrder(Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE orders SET completion_reported_by = ? WHERE id = ?", reporterId, orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(completionReportedByOf(orderId)).isNull();
	}

	@Test
	void timestampWithoutReporterIsRejected() {
		long orderId = insertOrder(Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now() WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(completionReportedAtOf(orderId)).isNull();
	}

	@Test
	void completedWithoutCompletedAtIsRejected() {
		long orderId = insertOrder(Instant.now());
		jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now(), completion_reported_by = ? WHERE id = ?",
			reporterId, orderId);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE orders SET status = 'COMPLETED' WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(statusOf(orderId)).isEqualTo("OPEN");
	}

	@Test
	void completedWithoutReportIsRejected() {
		long orderId = insertOrder(Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE orders SET status = 'COMPLETED', completed_at = now() WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(statusOf(orderId)).isEqualTo("OPEN");
	}

	@Test
	void reportedThenCompletedTakenOrderIsAccepted() {
		long orderId = insertOrder(Instant.now());

		jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now(), completion_reported_by = ? WHERE id = ?",
			reporterId, orderId);
		jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED', completed_at = now() WHERE id = ?", orderId);

		assertThat(statusOf(orderId)).isEqualTo("COMPLETED");
		assertThat(completionReportedByOf(orderId)).isEqualTo(reporterId);
	}

	@Test
	void deletingReporterAccountIsRejected() {
		long orderId = insertOrder(Instant.now());
		jdbcTemplate.update(
			"UPDATE orders SET completion_reported_at = now(), completion_reported_by = ? WHERE id = ?",
			reporterId, orderId);

		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", reporterId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(completionReportedByOf(orderId)).isEqualTo(reporterId);
	}

	@Test
	void negativeBuiltUnitsIsRejected() {
		long orderId = insertReportedOrder(10);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE orders SET built_units = -1 WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(builtUnitsOf(orderId)).isNull();
	}

	@Test
	void builtUnitsAboveQuantityIsRejected() {
		long orderId = insertReportedOrder(10);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE orders SET built_units = 11 WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(builtUnitsOf(orderId)).isNull();
	}

	@Test
	void builtUnitsWithoutReportIsRejected() {
		long orderId = insertOrder(Instant.now(), 10);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE orders SET built_units = 5 WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(builtUnitsOf(orderId)).isNull();
	}

	@Test
	void reportWithoutBuiltUnitsIsAccepted() {
		long orderId = insertReportedOrder(10);

		jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED', completed_at = now() WHERE id = ?", orderId);

		assertThat(statusOf(orderId)).isEqualTo("COMPLETED");
		assertThat(builtUnitsOf(orderId)).isNull();
	}

	@Test
	void zeroBuiltUnitsWithReportIsAccepted() {
		long orderId = insertReportedOrder(10);

		jdbcTemplate.update("UPDATE orders SET built_units = 0 WHERE id = ?", orderId);

		assertThat(builtUnitsOf(orderId)).isZero();
	}

	@Test
	void fullBuiltUnitsWithReportIsAccepted() {
		long orderId = insertReportedOrder(10);

		jdbcTemplate.update("UPDATE orders SET built_units = 10 WHERE id = ?", orderId);

		assertThat(builtUnitsOf(orderId)).isEqualTo(10);
	}

}

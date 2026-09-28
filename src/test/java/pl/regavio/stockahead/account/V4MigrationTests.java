package pl.regavio.stockahead.account;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V4MigrationTests {

	@Test
	void normalizesExistingEmailWithoutChangingAccountId() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToV3(postgres);
			long accountId = insertAccount(postgres, " Tech@Example.com ");

			migrateToLatest(postgres);

			try (Connection connection = connection(postgres);
					PreparedStatement statement = connection.prepareStatement("SELECT id, email FROM accounts")) {
				try (ResultSet rows = statement.executeQuery()) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getLong("id")).isEqualTo(accountId);
					assertThat(rows.getString("email")).isEqualTo("tech@example.com");
					assertThat(rows.next()).isFalse();
				}
			}
		}
	}

	@Test
	void canonicalCollisionStopsMigrationWithoutChangingRows() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToV3(postgres);
			insertAccount(postgres, "Tech@X.com");
			insertAccount(postgres, "tech@x.com");

			assertThatThrownBy(() -> migrateToLatest(postgres))
				.isInstanceOf(FlywayException.class)
				.hasStackTraceContaining("V4: kolizja kanonicznych e-maili");

			assertThat(accountEmails(postgres)).containsExactly("Tech@X.com", "tech@x.com");
		}
	}

	@Test
	void canonicalIndexRejectsCaseAndWhitespaceDuplicates() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToLatest(postgres);
			insertAccount(postgres, "a@x.com");

			assertCanonicalDuplicateRejected(postgres, "A@x.com");
			assertCanonicalDuplicateRejected(postgres, " a@x.com ");
		}
	}

	private void migrateToV3(PostgreSQLContainer postgres) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.target("3")
			.load()
			.migrate();
	}

	private void migrateToLatest(PostgreSQLContainer postgres) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.load()
			.migrate();
	}

	private long insertAccount(PostgreSQLContainer postgres, String email) throws SQLException {
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO accounts (email, password_hash, role) VALUES (?, 'hash', 'TECHNICIAN') RETURNING id")) {
			statement.setString(1, email);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private List<String> accountEmails(PostgreSQLContainer postgres) throws SQLException {
		List<String> emails = new ArrayList<>();
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement("SELECT email FROM accounts ORDER BY id");
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				emails.add(rows.getString(1));
			}
		}
		return emails;
	}

	private void assertCanonicalDuplicateRejected(PostgreSQLContainer postgres, String email) {
		assertThatThrownBy(() -> insertAccount(postgres, email))
			.isInstanceOf(SQLException.class)
			.satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23505"));
	}

	private Connection connection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

package pl.regavio.stockahead.account;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins V14: it refuses to run while {@code accounts} has rows, and the
 * {@code companies} table and {@code accounts.company_id} enforce the status
 * default, the status check, a non-null company and several managers.
 */
class V14MigrationTests {

	@Test
	void existingAccountStopsMigrationWithoutChangingAccounts() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, "13");
			execute(postgres, "INSERT INTO accounts (email, password_hash, role) VALUES ('kept@example.com', 'hash', 'MANAGER')");

			assertThatThrownBy(() -> migrate(postgres, null))
				.isInstanceOf(FlywayException.class)
				.hasStackTraceContaining("V14:");

			assertThat(strings(postgres, "SELECT email FROM accounts ORDER BY id")).containsExactly("kept@example.com");
			assertThat(strings(postgres,
					"SELECT column_name FROM information_schema.columns WHERE table_name = 'accounts' AND column_name = 'company_id'"))
				.isEmpty();
			assertThat(strings(postgres, "SELECT to_regclass('companies')::text")).containsExactly((String) null);
		}
	}

	@Test
	void emptyDatabaseMigratesCleanly() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, "13");

			migrate(postgres, null);

			assertThat(strings(postgres,
					"SELECT is_nullable FROM information_schema.columns WHERE table_name = 'accounts' AND column_name = 'company_id'"))
				.containsExactly("NO");
			assertThat(strings(postgres, "SELECT to_regclass('accounts_single_manager_idx')::text"))
				.containsExactly((String) null);
		}
	}

	@Test
	void newCompanyIsActiveByDefault() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, null);

			long companyId = insertCompany(postgres, "Default Status Company");

			assertThat(strings(postgres, "SELECT status FROM companies WHERE id = " + companyId))
				.containsExactly("ACTIVE");
		}
	}

	@Test
	void unknownCompanyStatusIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, null);

			assertRejected(() -> execute(postgres,
					"INSERT INTO companies (name, status) VALUES ('Odd Status Company', 'ARCHIVED')"), "23514");
		}
	}

	@Test
	void accountWithoutCompanyIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, null);

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role) VALUES ('orphan@example.com', 'hash', 'TECHNICIAN')"),
					"23502");
		}
	}

	@Test
	void managersOfTwoCompaniesAreBothAccepted() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, null);
			long first = insertCompany(postgres, "First Company");
			long second = insertCompany(postgres, "Second Company");

			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) VALUES "
					+ "('first-manager@example.com', 'hash', 'MANAGER', " + first + "), "
					+ "('second-manager@example.com', 'hash', 'MANAGER', " + second + ")");

			assertThat(strings(postgres, "SELECT email FROM accounts WHERE role = 'MANAGER' ORDER BY email"))
				.containsExactly("first-manager@example.com", "second-manager@example.com");
		}
	}

	private void migrate(PostgreSQLContainer postgres, String target) {
		var configuration = Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private long insertCompany(PostgreSQLContainer postgres, String name) throws SQLException {
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO companies (name) VALUES (?) RETURNING id")) {
			statement.setString(1, name);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private void execute(PostgreSQLContainer postgres, String sql) throws SQLException {
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.executeUpdate();
		}
	}

	private List<String> strings(PostgreSQLContainer postgres, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

	private void assertRejected(ThrowingCallable insert, String sqlState) {
		assertThatThrownBy(insert)
			.isInstanceOf(SQLException.class)
			.satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(sqlState));
	}

	private Connection connection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

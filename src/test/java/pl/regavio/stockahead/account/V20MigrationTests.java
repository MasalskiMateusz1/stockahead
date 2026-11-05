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
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins V20: an {@code OPERATOR} account exists without a company, at most
 * one operator exists, an operator never has a company, and managers and
 * technicians always have one.
 */
class V20MigrationTests {

	@Test
	void operatorWithoutCompanyIsAccepted() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);

			execute(postgres, "INSERT INTO accounts (email, password_hash, role) VALUES ('operator@example.com', 'hash', 'OPERATOR')");

			assertThat(strings(postgres, "SELECT email FROM accounts WHERE role = 'OPERATOR' AND company_id IS NULL"))
				.containsExactly("operator@example.com");
		}
	}

	@Test
	void secondOperatorIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			execute(postgres, "INSERT INTO accounts (email, password_hash, role) VALUES ('operator@example.com', 'hash', 'OPERATOR')");

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role) VALUES ('second-operator@example.com', 'hash', 'OPERATOR')"),
					"23505", "accounts_single_operator_idx");
			assertThat(strings(postgres, "SELECT email FROM accounts")).containsExactly("operator@example.com");
		}
	}

	@Test
	void operatorWithCompanyIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long companyId = insertCompany(postgres, "Operator Company");

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role, company_id) VALUES ('operator@example.com', 'hash', 'OPERATOR', "
							+ companyId + ")"),
					"23514", "accounts_operator_without_company_check");
		}
	}

	@Test
	void managerWithoutCompanyIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role) VALUES ('manager@example.com', 'hash', 'MANAGER')"),
					"23514", "accounts_operator_without_company_check");
		}
	}

	@Test
	void technicianWithoutCompanyIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role) VALUES ('technician@example.com', 'hash', 'TECHNICIAN')"),
					"23514", "accounts_operator_without_company_check");
		}
	}

	@Test
	void unknownRoleIsStillRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long companyId = insertCompany(postgres, "Odd Role Company");

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role, company_id) VALUES ('odd@example.com', 'hash', 'ADMIN', "
							+ companyId + ")"),
					"23514", "accounts_role_check");
		}
	}

	private void migrate(PostgreSQLContainer postgres) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.load()
			.migrate();
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

	private void assertRejected(ThrowingCallable insert, String sqlState, String constraint) {
		assertThatThrownBy(insert)
			.isInstanceOf(SQLException.class)
			.hasMessageContaining(constraint)
			.satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(sqlState));
	}

	private Connection connection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

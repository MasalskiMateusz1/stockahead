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
 * Pins V21: a released account ({@code email_released_at} set) keeps its
 * email but no longer counts toward canonical-email uniqueness, so a live
 * account may take the same email in any case or spacing; two live accounts
 * still may not share one; a released account is always inactive and never
 * the operator.
 */
class V21MigrationTests {

	@Test
	void liveAccountMayShareAReleasedAccountsEmailInAnyCaseOrSpacing() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long rejected = insertCompany(postgres, "Rejected Co");
			long fresh = insertCompany(postgres, "Fresh Co");
			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id, active, email_released_at) "
					+ "VALUES ('reused@example.com', 'hash', 'MANAGER', " + rejected + ", false, now())");

			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) "
					+ "VALUES (' Reused@Example.COM ', 'hash', 'MANAGER', " + fresh + ")");

			assertThat(strings(postgres,
					"SELECT company_id::text FROM accounts WHERE lower(btrim(email)) = 'reused@example.com' ORDER BY id"))
				.containsExactly(String.valueOf(rejected), String.valueOf(fresh));
		}
	}

	@Test
	void liveAccountMayShareAReleasedAccountsExactEmail() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long rejected = insertCompany(postgres, "Rejected Co");
			long fresh = insertCompany(postgres, "Fresh Co");
			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id, active, email_released_at) "
					+ "VALUES ('exact@example.com', 'hash', 'MANAGER', " + rejected + ", false, now())");

			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) "
					+ "VALUES ('exact@example.com', 'hash', 'MANAGER', " + fresh + ")");

			assertThat(strings(postgres, "SELECT email FROM accounts WHERE email = 'exact@example.com'")).hasSize(2);
		}
	}

	@Test
	void twoLiveAccountsWithTheSameCanonicalEmailAreRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long first = insertCompany(postgres, "First Co");
			long second = insertCompany(postgres, "Second Co");
			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) "
					+ "VALUES ('taken@example.com', 'hash', 'MANAGER', " + first + ")");

			assertRejected(() -> execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) "
					+ "VALUES (' Taken@Example.com ', 'hash', 'MANAGER', " + second + ")"),
					"23505", "accounts_email_canonical_idx");
			assertThat(strings(postgres, "SELECT email FROM accounts")).containsExactly("taken@example.com");
		}
	}

	@Test
	void releasedActiveAccountIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long companyId = insertCompany(postgres, "Rejected Co");

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role, company_id, active, email_released_at) "
							+ "VALUES ('active-released@example.com', 'hash', 'MANAGER', " + companyId + ", true, now())"),
					"23514", "accounts_released_inactive_check");
		}
	}

	@Test
	void releasingAnActiveAccountWithoutDeactivatingItIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);
			long companyId = insertCompany(postgres, "Rejected Co");
			execute(postgres, "INSERT INTO accounts (email, password_hash, role, company_id) "
					+ "VALUES ('manager@example.com', 'hash', 'MANAGER', " + companyId + ")");

			assertRejected(() -> execute(postgres,
					"UPDATE accounts SET email_released_at = now() WHERE email = 'manager@example.com'"),
					"23514", "accounts_released_inactive_check");
		}
	}

	@Test
	void releasedOperatorIsRejected() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);

			assertRejected(() -> execute(postgres,
					"INSERT INTO accounts (email, password_hash, role, active, email_released_at) "
							+ "VALUES ('operator@example.com', 'hash', 'OPERATOR', false, now())"),
					"23514", "accounts_operator_not_released_check");
		}
	}

	@Test
	void plainEmailUniqueConstraintIsGone() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres);

			assertThat(strings(postgres,
					"SELECT conname FROM pg_constraint WHERE conrelid = 'accounts'::regclass AND conname = 'accounts_email_key'"))
				.isEmpty();
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

	private void assertRejected(ThrowingCallable statement, String sqlState, String constraint) {
		assertThatThrownBy(statement)
			.isInstanceOf(SQLException.class)
			.hasMessageContaining(constraint)
			.satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(sqlState));
	}

	private Connection connection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

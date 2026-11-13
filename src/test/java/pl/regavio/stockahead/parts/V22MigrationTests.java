package pl.regavio.stockahead.parts;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class V22MigrationTests {

	@Test
	void resetsUnknownStockAndLiveReservationsButKeepsCatalogAndPickedHistory() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrate(postgres, "21");
			long company = insert(postgres, "INSERT INTO companies (name) VALUES ('Stock migration') RETURNING id");
			long part = insert(postgres, "INSERT INTO parts (name, quantity, company_id) VALUES ('Resistor', 10, "
					+ company + ") RETURNING id");
			long shelf = insert(postgres, "INSERT INTO part_locations (part_id, location, company_id) VALUES ("
					+ part + ", 'A1', " + company + ") RETURNING id");
			long project = insert(postgres, "INSERT INTO projects (name, company_id) VALUES ('Board', "
					+ company + ") RETURNING id");
			long order = insert(postgres, "INSERT INTO orders (project_id, company_id, quantity_units, priority, required_date) "
					+ "VALUES (" + project + ", " + company + ", 1, 'NORMAL', CURRENT_DATE) RETURNING id");
			long line = insert(postgres, "INSERT INTO order_lines (order_id, part_id, company_id, required_quantity, "
					+ "reserved_quantity, picked_quantity) VALUES (" + order + ", " + part + ", " + company
					+ ", 10, 7, 2) RETURNING id");

			migrate(postgres, null);
			assertThat(value(postgres, "SELECT quantity FROM parts WHERE id = " + part)).isZero();
			assertThat(value(postgres, "SELECT quantity FROM part_locations WHERE id = " + shelf)).isZero();
			assertThat(value(postgres, "SELECT reserved_quantity FROM order_lines WHERE id = " + line)).isZero();
			assertThat(value(postgres, "SELECT picked_quantity FROM order_lines WHERE id = " + line)).isEqualTo(2);
			assertThat(value(postgres, "SELECT count(*) FROM part_locations WHERE id = " + shelf)).isEqualTo(1);
		}
	}

	private void migrate(PostgreSQLContainer postgres, String target) {
		var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		if (target != null) {
			config.target(target);
		}
		config.load().migrate();
	}

	private long insert(PostgreSQLContainer postgres, String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getLong(1);
		}
	}

	private int value(PostgreSQLContainer postgres, String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getInt(1);
		}
	}
}

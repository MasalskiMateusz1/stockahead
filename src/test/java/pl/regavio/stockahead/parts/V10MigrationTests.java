package pl.regavio.stockahead.parts;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V10MigrationTests {

	@Test
	void unifiesSpellingAcrossPartsToTheOldestRow() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToV9(postgres);
			long first = insertPart(postgres, "Part X");
			long second = insertPart(postgres, "Part Y");
			long third = insertPart(postgres, "Part Z");
			insertLocation(postgres, first, "A1");
			insertLocation(postgres, second, "a1");
			insertLocation(postgres, third, "B2");

			migrateToLatest(postgres);

			assertThat(locationsOf(postgres, first)).containsExactly("A1");
			assertThat(locationsOf(postgres, second)).containsExactly("A1");
			assertThat(locationsOf(postgres, third)).containsExactly("B2");
		}
	}

	@Test
	void keepsTheLowestIdRowWhenOnePartHoldsCaseVariants() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToV9(postgres);
			long part = insertPart(postgres, "Part X");
			long kept = insertLocation(postgres, part, "a1");
			insertLocation(postgres, part, "A1");
			long other = insertLocation(postgres, part, "B2");

			migrateToLatest(postgres);

			assertThat(locationIdsOf(postgres, part)).containsExactly(kept, other);
			assertThat(locationsOf(postgres, part)).containsExactly("a1", "B2");
		}
	}

	@Test
	void indexRejectsACaseDuplicateOnOnePart() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
			postgres.start();
			migrateToLatest(postgres);
			long part = insertPart(postgres, "Part X");
			long otherPart = insertPart(postgres, "Part Y");
			insertLocation(postgres, part, "A1");

			assertThatThrownBy(() -> insertLocation(postgres, part, "a1"))
				.isInstanceOf(SQLException.class)
				.satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23505"));
			insertLocation(postgres, otherPart, "A1");
			assertThat(locationsOf(postgres, otherPart)).containsExactly("A1");
		}
	}

	private void migrateToV9(PostgreSQLContainer postgres) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.target("9")
			.load()
			.migrate();
	}

	private void migrateToLatest(PostgreSQLContainer postgres) {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.load()
			.migrate();
	}

	private long insertPart(PostgreSQLContainer postgres, String name) throws SQLException {
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO parts (name, quantity) VALUES (?, 0) RETURNING id")) {
			statement.setString(1, name);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private long insertLocation(PostgreSQLContainer postgres, long partId, String location) throws SQLException {
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO part_locations (part_id, location) VALUES (?, ?) RETURNING id")) {
			statement.setLong(1, partId);
			statement.setString(2, location);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private List<String> locationsOf(PostgreSQLContainer postgres, long partId) throws SQLException {
		List<String> locations = new ArrayList<>();
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"SELECT location FROM part_locations WHERE part_id = ? ORDER BY id")) {
			statement.setLong(1, partId);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					locations.add(rows.getString(1));
				}
			}
		}
		return locations;
	}

	private List<Long> locationIdsOf(PostgreSQLContainer postgres, long partId) throws SQLException {
		List<Long> ids = new ArrayList<>();
		try (Connection connection = connection(postgres);
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id FROM part_locations WHERE part_id = ? ORDER BY id")) {
			statement.setLong(1, partId);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					ids.add(rows.getLong(1));
				}
			}
		}
		return ids;
	}

	private Connection connection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

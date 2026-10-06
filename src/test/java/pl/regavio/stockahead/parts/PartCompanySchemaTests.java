package pl.regavio.stockahead.parts;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V15 company constraints on the parts catalog at the PostgreSQL
 * level: part names are unique per company, and a location row always
 * carries its part's company. Deliberately not {@code @Transactional}: every
 * {@code JdbcTemplate} call auto-commits, so the constraint fires for real.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
class PartCompanySchemaTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CompanyFixtures companyFixtures;

	private Company company;

	private Company otherCompany;

	@BeforeEach
	void setUp() {
		company = companyFixtures.company("PartCompanySchemaTests Co");
		otherCompany = companyFixtures.company("PartCompanySchemaTests Other Co");
	}

	@AfterEach
	void tearDown() {
		companyFixtures.cleanUp();
	}

	private long insertPart(Company owner, String name) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO parts (name, quantity, company_id) VALUES (?, 10, ?) RETURNING id", Long.class, name,
				owner.getId());
	}

	private int partsNamed(String name) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM parts WHERE name = ? AND company_id IN (?, ?)",
				Integer.class, name, company.getId(), otherCompany.getId());
	}

	@Test
	void twoCompaniesEachHoldAPartWithTheSameNameButOneCompanyCannotHoldItTwice() {
		Part first = companyFixtures.part(company, "R 10k", 5, "A1");
		Part second = companyFixtures.part(otherCompany, "R 10k", 7, "A1");

		assertThat(first.getId()).isNotEqualTo(second.getId());
		assertThat(jdbcTemplate.queryForList("SELECT company_id FROM part_locations WHERE part_id IN (?, ?) "
				+ "ORDER BY part_id", Long.class, first.getId(), second.getId()))
			.containsExactly(company.getId(), otherCompany.getId());

		assertThatThrownBy(() -> insertPart(company, "R 10k")).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(partsNamed("R 10k")).isEqualTo(2);
	}

	@Test
	void locationWhoseCompanyDiffersFromItsPartsCompanyIsRejected() {
		long partId = insertPart(company, "C 100n");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO part_locations (part_id, location, company_id) VALUES (?, ?, ?)", partId, "B2",
				otherCompany.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM part_locations WHERE part_id = ?",
				Integer.class, partId)).isZero();
	}

}

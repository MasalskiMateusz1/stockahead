package pl.regavio.stockahead.parts;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class StockWorkflowIntegrationTests {

	@Autowired private CompanyFixtures fixtures;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MockMvc mvc;

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM stock_corrections");
		fixtures.cleanUp();
	}

	@Test
	void receiptTransferCountAndSplitPickKeepEveryBalanceInSync() throws Exception {
		Company company = fixtures.company("Stock flow");
		fixtures.account(company, "stock-manager@example.com", "correct-password", Role.MANAGER, true);
		MockHttpSession session = login("stock-manager@example.com");
		mvc.perform(post("/parts").session(session).with(csrf())
			.param("name", "Resistor").param("quantity", "8")
			.param("locations", "A1\nB1").param("initialLocation", "A1"))
			.andExpect(status().is3xxRedirection());
		Long part = jdbc.queryForObject("SELECT id FROM parts WHERE company_id = ? AND name = 'Resistor'",
			Long.class, company.getId());
		Long a = shelf(part, "A1");
		Long b = shelf(part, "B1");
		assertBalanced(part, 8, 8, 0);

		mvc.perform(post("/parts/{id}/transfer", part).session(session).with(csrf())
			.param("sourceId", a.toString()).param("destinationId", b.toString()).param("quantity", "3"))
			.andExpect(status().is3xxRedirection());
		assertBalanced(part, 8, 5, 3);

		mvc.perform(post("/deliveries").session(session).with(csrf()).param("rows", "1")
			.param("partId0", part.toString()).param("quantity0", "2").param("location0", "B1"))
			.andExpect(status().is3xxRedirection());
		assertBalanced(part, 10, 5, 5);

		mvc.perform(post("/parts/{id}/correction/set", part).session(session).with(csrf())
			.param("locationId", a.toString()).param("expectedQuantity", "5")
			.param("newQuantity", "6").param("reason", "Spis"))
			.andExpect(status().is3xxRedirection());
		assertBalanced(part, 11, 6, 5);

		long project = jdbc.queryForObject("INSERT INTO projects (name, company_id) VALUES ('Board', ?) RETURNING id",
			Long.class, company.getId());
		long order = jdbc.queryForObject("INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date) "
				+ "VALUES (?, ?, 1, 'NORMAL', ?) RETURNING id", Long.class, company.getId(), project,
			LocalDate.now().plusDays(2));
		long line = jdbc.queryForObject("INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, reserved_quantity) "
				+ "VALUES (?, ?, ?, 8, 8) RETURNING id", Long.class, company.getId(), order, part);
		mvc.perform(post("/picking/{order}/lines/{line}/pick", order, line).session(session).with(csrf())
			.param("locationId", a.toString()).param("quantity", "6"))
			.andExpect(status().is3xxRedirection());
		mvc.perform(post("/picking/{order}/lines/{line}/pick", order, line).session(session).with(csrf())
			.param("locationId", b.toString()).param("quantity", "2"))
			.andExpect(status().is3xxRedirection());
		assertBalanced(part, 3, 0, 3);
		assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class, line))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class, line))
			.isEqualTo(8);

		mvc.perform(post("/orders/{id}/cancel", order).session(session).with(csrf())
			.param("seenPicked_" + line, "8").param("returned_" + line, "3")
			.param("location_" + line, a.toString()))
			.andExpect(status().is3xxRedirection());
		assertBalanced(part, 6, 3, 3);
	}

	@Test
	void transferAndShelfActionsRespectRoleAndCompanyOwnership() throws Exception {
		Company first = fixtures.company("Stock boundary A");
		Company second = fixtures.company("Stock boundary B");
		fixtures.account(first, "stock-a-manager@example.com", "correct-password", Role.MANAGER, true);
		fixtures.account(first, "stock-a-tech@example.com", "correct-password", Role.TECHNICIAN, true);
		fixtures.account(second, "stock-b-manager@example.com", "correct-password", Role.MANAGER, true);
		Part partA = fixtures.part(first, "Resistor", 4, "A1", "B1");
		Part partB = fixtures.part(second, "Resistor", 5, "A1");
		Long aSource = shelf(partA.getId(), "A1");
		Long aDestination = shelf(partA.getId(), "B1");
		Long bShelf = shelf(partB.getId(), "A1");
		MockHttpSession managerA = login("stock-a-manager@example.com");
		MockHttpSession technicianA = login("stock-a-tech@example.com");
		MockHttpSession managerB = login("stock-b-manager@example.com");

		mvc.perform(get("/parts/{id}/transfer", partA.getId()).session(technicianA))
			.andExpect(status().isForbidden());
		mvc.perform(post("/parts/{id}/transfer", partA.getId()).session(technicianA).with(csrf())
			.param("sourceId", aSource.toString()).param("destinationId", aDestination.toString())
			.param("quantity", "1")).andExpect(status().isForbidden());
		mvc.perform(get("/parts/{id}/transfer", partA.getId()).session(managerB))
			.andExpect(status().isNotFound());
		mvc.perform(post("/parts/{id}/transfer", partA.getId()).session(managerB).with(csrf())
			.param("sourceId", aSource.toString()).param("destinationId", aDestination.toString())
			.param("quantity", "1")).andExpect(status().isNotFound());
		mvc.perform(post("/parts/{id}/transfer", partA.getId()).session(managerA).with(csrf())
			.param("sourceId", bShelf.toString()).param("destinationId", aDestination.toString())
			.param("quantity", "1")).andExpect(status().isNotFound());
		assertBalanced(partA.getId(), 4, 4, 0);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE id = ?", Integer.class, bShelf))
			.isEqualTo(5);
	}

	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) mvc.perform(formLogin().user(email).password("correct-password"))
			.andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession();
	}

	private Long shelf(Long part, String name) {
		return jdbc.queryForObject("SELECT id FROM part_locations WHERE part_id = ? AND location = ?",
			Long.class, part, name);
	}

	private void assertBalanced(Long part, int total, int a, int b) {
		assertThat(jdbc.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part)).isEqualTo(total);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ? AND location = 'A1'",
			Integer.class, part)).isEqualTo(a);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ? AND location = 'B1'",
			Integer.class, part)).isEqualTo(b);
		assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM part_locations WHERE part_id = ?", Long.class, part))
			.isEqualTo((long) total);
	}
}

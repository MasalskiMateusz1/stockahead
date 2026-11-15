package pl.regavio.stockahead.orders;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;
import pl.regavio.stockahead.parts.Part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stock leaving a named shelf (picks) and returning to a chosen shelf
 * (cancellation returns): the shelf, the part total and the line's live
 * reservation move together, and foreign or missing shelves change nothing.
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class StockOutflowIntegrationTests {

	private static final String PASSWORD = "correct-password";

	private static final String MANAGER = "outflow-manager@example.com";

	private static final String TECHNICIAN = "outflow-tech@example.com";

	private static final String OTHER_TECHNICIAN = "outflow-other-tech@example.com";

	@Autowired private CompanyFixtures fixtures;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MockMvc mvc;

	private Company company;

	private Company other;

	private Long project;

	@BeforeEach
	void setUp() {
		company = fixtures.company("Outflow");
		other = fixtures.company("Outflow other");
		fixtures.account(company, MANAGER, PASSWORD, Role.MANAGER, true);
		fixtures.account(company, TECHNICIAN, PASSWORD, Role.TECHNICIAN, true);
		fixtures.account(other, OTHER_TECHNICIAN, PASSWORD, Role.TECHNICIAN, true);
		project = jdbc.queryForObject("INSERT INTO projects (name, company_id) VALUES ('Board', ?) RETURNING id",
				Long.class, company.getId());
	}

	@AfterEach
	void cleanUp() {
		fixtures.cleanUp();
	}

	@Test
	void pickRejectsMoreThanTheShelfHoldsAMissingShelfAndAForeignShelfWithoutMutation() throws Exception {
		Long part = stockedPart();
		Long order = order("NORMAL");
		Long line = line(order, part, 8, 8);
		Part foreign = fixtures.part(other, "Resistor", 2, "A1");
		MockHttpSession technician = login(TECHNICIAN);

		// The part holds 8 and the line reserves 8, but B1 holds only 3.
		pick(technician, order, line, shelf(part, "B1"), "4").andExpect(status().isOk())
			.andExpect(content().string(containsString("Nie można pobrać więcej niż 3 szt. z lokalizacji B1")));
		mvc.perform(post("/picking/{order}/lines/{line}/pick", order, line).session(technician).with(csrf())
			.param("quantity", "1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Wybierz lokalizację, z której pobierasz części.")));
		pick(technician, order, line, shelf(foreign.getId(), "A1"), "1").andExpect(status().isNotFound());
		pick(login(OTHER_TECHNICIAN), order, line, shelf(part, "A1"), "1").andExpect(status().isNotFound());

		assertShelves(part, 8, 5, 3);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ?", Integer.class,
				foreign.getId())).isEqualTo(2);
		assertLine(line, 8, 0);
		assertThat(jdbc.queryForObject("SELECT taken_at IS NULL FROM orders WHERE id = ?", Boolean.class, order))
			.isTrue();
	}

	@Test
	void splitPicksAndAReturnToAChosenShelfKeepShelvesTotalsAndReservations() throws Exception {
		Long part = stockedPart();
		Long order = order("HIGH");
		Long line = line(order, part, 8, 8);
		Long waiting = order("LOW");
		Long waitingLine = line(waiting, part, 5, 0);
		MockHttpSession technician = login(TECHNICIAN);
		MockHttpSession manager = login(MANAGER);

		// More than half the reservation first, then exactly the remainder from the other shelf.
		pick(technician, order, line, shelf(part, "A1"), "5").andExpect(status().is3xxRedirection());
		assertShelves(part, 3, 0, 3);
		assertLine(line, 3, 5);
		pick(technician, order, line, shelf(part, "B1"), "3").andExpect(status().is3xxRedirection());
		assertShelves(part, 0, 0, 0);
		assertLine(line, 0, 8);

		// A positive return without a shelf, or onto another company's shelf, changes nothing.
		cancel(manager, order, line, "8", "3", null).andExpect(status().isOk())
			.andExpect(content().string(containsString("Wskaż lokalizację zwrotu")));
		Part foreign = fixtures.part(other, "Resistor", 0, "A1");
		cancel(manager, order, line, "8", "3", shelf(foreign.getId(), "A1")).andExpect(status().isNotFound());
		assertShelves(part, 0, 0, 0);
		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
			.isEqualTo("OPEN");

		cancel(manager, order, line, "8", "3", shelf(part, "B1")).andExpect(status().is3xxRedirection());
		assertShelves(part, 3, 0, 3);
		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
			.isEqualTo("CANCELLED");
		// The returned units are reallocated part-wide to the waiting order.
		assertLine(waitingLine, 3, 0);
	}

	@Test
	void aZeroReturnNeedsNoShelf() throws Exception {
		Long part = stockedPart();
		Long order = order("NORMAL");
		Long line = line(order, part, 8, 8);
		pick(login(TECHNICIAN), order, line, shelf(part, "A1"), "2").andExpect(status().is3xxRedirection());

		cancel(login(MANAGER), order, line, "2", "0", null).andExpect(status().is3xxRedirection());
		assertShelves(part, 6, 3, 3);
	}

	@Test
	void concurrentPicksFromOneShelfNeverTakeMoreThanItHolds() throws Exception {
		Long part = stockedPart();
		Long first = order("HIGH");
		Long firstLine = line(first, part, 5, 5);
		Long second = order("NORMAL");
		Long secondLine = line(second, part, 3, 3);
		MockHttpSession technician = login(TECHNICIAN);
		Long a1 = shelf(part, "A1");

		for (int iteration = 0; iteration < 5; iteration++) {
			jdbc.update("UPDATE part_locations SET quantity = CASE location WHEN 'A1' THEN 5 ELSE 3 END WHERE part_id = ?",
					part);
			jdbc.update("UPDATE parts SET quantity = 8 WHERE id = ?", part);
			jdbc.update("UPDATE order_lines SET reserved_quantity = required_quantity, picked_quantity = 0 "
					+ "WHERE id IN (?, ?)", firstLine, secondLine);
			CountDownLatch start = new CountDownLatch(1);
			int firstStatus;
			int secondStatus;
			try (var pool = Executors.newFixedThreadPool(2)) {
				// Each pick alone fits A1 (4 of 5, 3 of 5); together they would need 7.
				Future<Integer> a = pool.submit(() -> {
					start.await();
					return pick(technician, first, firstLine, a1, "4").andReturn().getResponse().getStatus();
				});
				Future<Integer> b = pool.submit(() -> {
					start.await();
					return pick(technician, second, secondLine, a1, "3").andReturn().getResponse().getStatus();
				});
				start.countDown();
				firstStatus = a.get(20, TimeUnit.SECONDS);
				secondStatus = b.get(20, TimeUnit.SECONDS);
			}
			assertThat((firstStatus == 302 ? 1 : 0) + (secondStatus == 302 ? 1 : 0))
				.as("iteration %d: exactly one pick applied", iteration).isEqualTo(1);
			int a1After = jdbc.queryForObject("SELECT quantity FROM part_locations WHERE id = ?", Integer.class, a1);
			assertThat(a1After).as("iteration %d: A1", iteration).isEqualTo(firstStatus == 302 ? 1 : 2);
			assertThat(jdbc.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part))
				.as("iteration %d: total", iteration).isEqualTo(a1After + 3);
			assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM part_locations WHERE part_id = ?", Long.class,
					part)).as("iteration %d: shelf sum", iteration).isEqualTo((long) a1After + 3);
		}
	}

	/** Resistor: 5 on A1, 3 on B1. */
	private Long stockedPart() {
		Part part = fixtures.part(company, "Resistor", 5, "A1", "B1");
		jdbc.update("UPDATE part_locations SET quantity = 3 WHERE part_id = ? AND location = 'B1'", part.getId());
		jdbc.update("UPDATE parts SET quantity = 8 WHERE id = ?", part.getId());
		return part.getId();
	}

	private Long order(String priority) {
		return jdbc.queryForObject("INSERT INTO orders (company_id, project_id, quantity_units, priority, required_date) "
				+ "VALUES (?, ?, 1, ?, ?) RETURNING id", Long.class, company.getId(), project, priority,
				LocalDate.now().plusDays(2));
	}

	private Long line(Long order, Long part, int required, int reserved) {
		return jdbc.queryForObject("INSERT INTO order_lines (company_id, order_id, part_id, required_quantity, "
				+ "reserved_quantity) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, company.getId(), order, part,
				required, reserved);
	}

	private ResultActions pick(MockHttpSession session, Long order, Long line, Long shelf, String quantity)
			throws Exception {
		return mvc.perform(post("/picking/{order}/lines/{line}/pick", order, line).session(session).with(csrf())
			.param("locationId", shelf.toString()).param("quantity", quantity));
	}

	private ResultActions cancel(MockHttpSession session, Long order, Long line, String seenPicked, String returned,
			Long shelf) throws Exception {
		var request = post("/orders/{id}/cancel", order).session(session).with(csrf())
			.param("seenPicked_" + line, seenPicked).param("returned_" + line, returned);
		if (shelf != null) {
			request.param("location_" + line, shelf.toString());
		}
		return mvc.perform(request);
	}

	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) mvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession();
	}

	private Long shelf(Long part, String name) {
		return jdbc.queryForObject("SELECT id FROM part_locations WHERE part_id = ? AND location = ?", Long.class,
				part, name);
	}

	private void assertShelves(Long part, int total, int a1, int b1) {
		assertThat(jdbc.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part))
			.as("part total").isEqualTo(total);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ? AND location = 'A1'",
				Integer.class, part)).as("A1").isEqualTo(a1);
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ? AND location = 'B1'",
				Integer.class, part)).as("B1").isEqualTo(b1);
	}

	private void assertLine(Long line, int reserved, int picked) {
		assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM order_lines WHERE id = ?", Integer.class, line))
			.as("reserved").isEqualTo(reserved);
		assertThat(jdbc.queryForObject("SELECT picked_quantity FROM order_lines WHERE id = ?", Integer.class, line))
			.as("picked").isEqualTo(picked);
	}

}

package pl.regavio.stockahead.parts;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stock entering and moving between shelves: creation, delivery, CSV import,
 * shelf edits, transfers and per-shelf corrections keep every shelf balance
 * and the part total ({@code parts.quantity = SUM(part_locations.quantity)}).
 */
@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
@AutoConfigureMockMvc
class StockInflowIntegrationTests {

	private static final String MANAGER = "inflow-manager@example.com";

	private static final String OTHER_MANAGER = "inflow-other@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String CSV_HEADER = "Nazwa;Ilość;Lokalizacja\r\n";

	@Autowired private CompanyFixtures fixtures;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MockMvc mvc;

	private Company company;

	private MockHttpSession manager;

	@BeforeEach
	void setUp() throws Exception {
		company = fixtures.company("Inflow");
		fixtures.account(company, MANAGER, PASSWORD, Role.MANAGER, true);
		manager = login(MANAGER);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM stock_corrections");
		fixtures.cleanUp();
	}

	@Test
	void creatingWithPositiveStockRequiresAListedInitialLocation() throws Exception {
		mvc.perform(post("/parts").session(manager).with(csrf())
			.param("name", "Resistor").param("quantity", "5").param("locations", "A1\nB1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("wskaż lokalizację początkową")));
		mvc.perform(post("/parts").session(manager).with(csrf())
			.param("name", "Resistor").param("quantity", "5").param("locations", "A1\nB1")
			.param("initialLocation", "C1"))
			.andExpect(status().isOk());
		mvc.perform(post("/parts").session(manager).with(csrf())
			.param("name", "Resistor").param("quantity", "0").param("locations", "A1")
			.param("initialLocation", "A1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Dla zerowego stanu nie podawaj lokalizacji początkowej.")));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM parts WHERE company_id = ?", Integer.class,
				company.getId())).isZero();

		// The destination matches a listed location regardless of letter case.
		mvc.perform(post("/parts").session(manager).with(csrf())
			.param("name", "Resistor").param("quantity", "5").param("locations", "A1\nB1")
			.param("initialLocation", "b1"))
			.andExpect(status().is3xxRedirection());
		Long part = partId("Resistor");
		assertShelves(part, 5, "A1", 0, "B1", 5);
	}

	@Test
	void deliveryKeepsEachRowOnItsShelfAndRejectsARowWithoutLocation() throws Exception {
		Part part = fixtures.part(company, "Resistor", 0, "A1", "B1");

		mvc.perform(post("/deliveries").session(manager).with(csrf()).param("rows", "3")
			.param("partId0", part.getId().toString()).param("quantity0", "3").param("location0", "A1")
			.param("partId1", part.getId().toString()).param("quantity1", "4").param("location1", "B1")
			.param("partId2", part.getId().toString()).param("quantity2", "2").param("location2", "a1"))
			.andExpect(status().is3xxRedirection());
		assertShelves(part.getId(), 9, "A1", 5, "B1", 4);

		mvc.perform(post("/deliveries").session(manager).with(csrf()).param("rows", "2")
			.param("partId0", part.getId().toString()).param("quantity0", "3").param("location0", "A1")
			.param("partId1", part.getId().toString()).param("quantity1", "4").param("location1", " "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Wiersz 2: podaj lokalizację, na którą trafia dostawa.")));
		assertShelves(part.getId(), 9, "A1", 5, "B1", 4);
	}

	@Test
	void importKeepsEachRowOnItsShelfAndRejectsAPositiveRowWithoutLocation() throws Exception {
		Part part = fixtures.part(company, "Resistor", 2, "A1");

		preview(CSV_HEADER + "Resistor;3;A1\r\nResistor;4;C1\r\nCapacitor;6;D1\r\n")
			.andExpect(content().string(containsString("C1: 4")));
		confirm().andExpect(status().is3xxRedirection());
		assertShelves(part.getId(), 9, "A1", 5, "C1", 4);
		Long capacitor = partId("Capacitor");
		assertThat(shelfQuantity(capacitor, "D1")).isEqualTo(6);
		assertThat(total(capacitor)).isEqualTo(6);

		mvc.perform(multipart("/parts/import").file(csv(CSV_HEADER + "Resistor;3;\r\n")).session(manager).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("podaj lokalizację dla dodatniej ilości")));
		assertShelves(part.getId(), 9, "A1", 5, "C1", 4);
	}

	@Test
	void importPreviewGoesStaleAfterATransferThatKeepsThePartTotal() throws Exception {
		Part part = fixtures.part(company, "Resistor", 8, "A1", "B1");
		preview(CSV_HEADER + "Resistor;2;A1\r\n").andExpect(status().isOk());

		transfer(part.getId(), shelfId(part.getId(), "A1"), shelfId(part.getId(), "B1"), "3")
			.andExpect(status().is3xxRedirection());
		assertShelves(part.getId(), 8, "A1", 5, "B1", 3);

		// The part total is unchanged (8), but the A1 balance the preview showed is not.
		confirm().andExpect(status().isOk())
			.andExpect(content().string(containsString("Stany zmieniły się od podglądu")))
			.andExpect(content().string(containsString("A1: 7")));
		assertShelves(part.getId(), 8, "A1", 5, "B1", 3);

		confirm().andExpect(status().is3xxRedirection());
		assertShelves(part.getId(), 10, "A1", 7, "B1", 3);
	}

	@Test
	void editRenamesAShelfWithItsStockAndRefusesToRemoveAStockedShelf() throws Exception {
		Part part = fixtures.part(company, "Resistor", 4, "A1", "B1");
		Long a1 = shelfId(part.getId(), "A1");
		Long b1 = shelfId(part.getId(), "B1");

		mvc.perform(post("/parts/{id}", part.getId()).session(manager).with(csrf())
			.param("name", "Resistor")
			.param("locationId", a1.toString(), b1.toString())
			.param("locationName", "C9", "")
			.param("newLocations", "D1"))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForObject("SELECT location FROM part_locations WHERE id = ?", String.class, a1))
			.isEqualTo("C9");
		assertThat(shelfQuantity(part.getId(), "C9")).isEqualTo(4);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM part_locations WHERE id = ?", Integer.class, b1))
			.isZero();
		assertThat(shelfQuantity(part.getId(), "D1")).isZero();

		Long d1 = shelfId(part.getId(), "D1");
		// Blanking the stocked row, or leaving it out entirely, must not discard its 4 units.
		mvc.perform(post("/parts/{id}", part.getId()).session(manager).with(csrf())
			.param("name", "Resistor")
			.param("locationId", a1.toString(), d1.toString())
			.param("locationName", "", "D1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nie można usunąć lokalizacji „C9”, bo ma stan 4 szt.")));
		mvc.perform(post("/parts/{id}", part.getId()).session(manager).with(csrf())
			.param("name", "Resistor")
			.param("locationId", d1.toString())
			.param("locationName", "D1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Nie można usunąć lokalizacji „C9”")));
		assertThat(shelfQuantity(part.getId(), "C9")).isEqualTo(4);
		assertThat(total(part.getId())).isEqualTo(4);
	}

	@Test
	void editOfAPartWithoutLocationsAddsTheNewOnes() throws Exception {
		Part part = fixtures.part(company, "Bare part", 0);

		mvc.perform(post("/parts/{id}", part.getId()).session(manager).with(csrf())
			.param("name", "Bare part")
			.param("newLocations", "E1\nE2"))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForList("SELECT location FROM part_locations WHERE part_id = ? ORDER BY location",
				String.class, part.getId())).containsExactly("E1", "E2");
	}

	@Test
	void renamingAShelfTakesTheCompanySpellingOfThatShelf() throws Exception {
		fixtures.part(company, "Other", 0, "Regał A1");
		Part part = fixtures.part(company, "Resistor", 3, "B1");
		Long b1 = shelfId(part.getId(), "B1");

		mvc.perform(post("/parts/{id}", part.getId()).session(manager).with(csrf())
			.param("name", "Resistor")
			.param("locationId", b1.toString())
			.param("locationName", "regał a1"))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForObject("SELECT location FROM part_locations WHERE id = ?", String.class, b1))
			.isEqualTo("Regał A1");
		assertThat(jdbc.queryForObject("SELECT quantity FROM part_locations WHERE id = ?", Integer.class, b1))
			.isEqualTo(3);
	}

	@Test
	void transferRejectsInsufficientStockAndTheSameShelfWithoutMutation() throws Exception {
		Part part = fixtures.part(company, "Resistor", 4, "A1", "B1");
		Long a1 = shelfId(part.getId(), "A1");
		Long b1 = shelfId(part.getId(), "B1");

		transfer(part.getId(), a1, b1, "5").andExpect(status().isOk())
			.andExpect(content().string(containsString("Za mało sztuk w lokalizacji źródłowej")));
		transfer(part.getId(), a1, a1, "1").andExpect(status().isOk())
			.andExpect(content().string(containsString("Lokalizacja źródłowa i docelowa muszą być różne.")));
		transfer(part.getId(), a1, b1, "0").andExpect(status().isOk())
			.andExpect(content().string(containsString("podaj dodatnią liczbę sztuk")));
		transfer(part.getId(), a1, b1, "x").andExpect(status().isOk());
		assertShelves(part.getId(), 4, "A1", 4, "B1", 0);

		// A stale form: the source was emptied by another transfer meanwhile.
		transfer(part.getId(), a1, b1, "4").andExpect(status().is3xxRedirection());
		transfer(part.getId(), a1, b1, "4").andExpect(status().isOk());
		assertShelves(part.getId(), 4, "A1", 0, "B1", 4);
	}

	@Test
	void correctionCountsOneShelfAndRejectsAStaleOrForeignShelf() throws Exception {
		Company other = fixtures.company("Inflow other");
		fixtures.account(other, OTHER_MANAGER, PASSWORD, Role.MANAGER, true);
		Part part = fixtures.part(company, "Resistor", 4, "A1", "B1");
		Part foreign = fixtures.part(other, "Resistor", 2, "A1");
		Long b1 = shelfId(part.getId(), "B1");

		mvc.perform(post("/parts/{id}/correction/set", part.getId()).session(manager).with(csrf())
			.param("locationId", b1.toString()).param("expectedQuantity", "0")
			.param("newQuantity", "3").param("reason", "Spis"))
			.andExpect(status().is3xxRedirection());
		assertShelves(part.getId(), 7, "A1", 4, "B1", 3);
		assertThat(jdbc.queryForObject("SELECT location_name FROM stock_corrections WHERE part_id = ?", String.class,
				part.getId())).isEqualTo("B1");

		// The form showed B1 = 0; it is now 3.
		mvc.perform(post("/parts/{id}/correction/set", part.getId()).session(manager).with(csrf())
			.param("locationId", b1.toString()).param("expectedQuantity", "0")
			.param("newQuantity", "1").param("reason", "Spis"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("zmienił się w międzyczasie")));
		mvc.perform(post("/parts/{id}/correction/adjust", part.getId()).session(manager).with(csrf())
			.param("locationId", shelfId(foreign.getId(), "A1").toString())
			.param("delta", "-1").param("reason", "Spis"))
			.andExpect(status().isNotFound());
		mvc.perform(post("/parts/{id}/correction/adjust", part.getId()).session(login(OTHER_MANAGER)).with(csrf())
			.param("locationId", b1.toString()).param("delta", "-1").param("reason", "Spis"))
			.andExpect(status().isNotFound());
		assertShelves(part.getId(), 7, "A1", 4, "B1", 3);
		assertThat(shelfQuantity(foreign.getId(), "A1")).isEqualTo(2);
	}

	private ResultActions transfer(Long part, Long source, Long destination, String quantity) throws Exception {
		return mvc.perform(post("/parts/{id}/transfer", part).session(manager).with(csrf())
			.param("sourceId", source.toString()).param("destinationId", destination.toString())
			.param("quantity", quantity));
	}

	private ResultActions preview(String text) throws Exception {
		return mvc.perform(multipart("/parts/import").file(csv(text)).session(manager).with(csrf()))
			.andExpect(status().isOk());
	}

	private ResultActions confirm() throws Exception {
		PendingImport pending = (PendingImport) manager.getAttribute(PendingImport.SESSION_ATTRIBUTE);
		return mvc.perform(post("/parts/import/confirm").session(manager).with(csrf()).param("token", pending.token()));
	}

	private static MockMultipartFile csv(String text) {
		return new MockMultipartFile("file", "czesci.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
	}

	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) mvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession();
	}

	private Long partId(String name) {
		return jdbc.queryForObject("SELECT id FROM parts WHERE company_id = ? AND name = ?", Long.class,
				company.getId(), name);
	}

	private Long shelfId(Long part, String name) {
		return jdbc.queryForObject("SELECT id FROM part_locations WHERE part_id = ? AND location = ?", Long.class,
				part, name);
	}

	private int shelfQuantity(Long part, String name) {
		return jdbc.queryForObject("SELECT quantity FROM part_locations WHERE part_id = ? AND location = ?",
				Integer.class, part, name);
	}

	private int total(Long part) {
		return jdbc.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, part);
	}

	private void assertShelves(Long part, int total, String first, int firstQuantity, String second,
			int secondQuantity) {
		assertThat(total(part)).as("part total").isEqualTo(total);
		assertThat(shelfQuantity(part, first)).as("shelf %s", first).isEqualTo(firstQuantity);
		assertThat(shelfQuantity(part, second)).as("shelf %s", second).isEqualTo(secondQuantity);
		assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM part_locations WHERE part_id = ?", Long.class, part))
			.as("shelf sum").isEqualTo((long) total);
	}

}

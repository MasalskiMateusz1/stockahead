package pl.regavio.stockahead.parts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.CompanyFixtures;
import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({ TestcontainersConfiguration.class, CompanyFixtures.class })
@SpringBootTest
class StockLocationIntegrationTests {

	@Autowired private CompanyFixtures fixtures;
	@Autowired private StockService stock;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private PartRepository partRepository;

	@AfterEach
	void cleanUp() {
		fixtures.cleanUp();
	}

	@Test
	void transferKeepsAggregateAndRejectsForeignLocation() {
		Company a = fixtures.company("Shelf test A");
		Company b = fixtures.company("Shelf test B");
		Part part = fixtures.part(a, "Resistor", 8, "A1", "B1");
		Part foreign = fixtures.part(b, "Resistor", 3, "A1");
		Long source = shelfId(part.getId(), "A1");
		Long destination = shelfId(part.getId(), "B1");
		Long foreignShelf = shelfId(foreign.getId(), "A1");

		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			Part locked = stock.lock(a.getId(), part.getId());
			stock.transferLocked(locked, stock.location(locked, a.getId(), source),
					stock.location(locked, a.getId(), destination), 5);
		});
		assertThat(quantity(source)).isEqualTo(3);
		assertThat(quantity(destination)).isEqualTo(5);
		assertThat(total(part.getId())).isEqualTo(8);
		assertThat(quantity(foreignShelf)).isEqualTo(3);
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
			Part locked = stock.lock(a.getId(), part.getId());
			stock.location(locked, a.getId(), foreignShelf);
		})).hasMessageContaining("404");
	}

	@Test
	void concurrentWritersPreserveShelfSumAndDatabaseRejectsNegativeShelf() throws Exception {
		Company company = fixtures.company("Shelf concurrency");
		Part part = fixtures.part(company, "Capacitor", 5, "A1", "B1");
		Long source = shelfId(part.getId(), "A1");
		Long destination = shelfId(part.getId(), "B1");
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> move(company.getId(), part.getId(), source, destination, 3, start));
			var second = pool.submit(() -> move(company.getId(), part.getId(), source, destination, 3, start));
			start.countDown();
			int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
					+ (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
			assertThat(successes).isEqualTo(1);
		}
		assertThat(quantity(source)).isEqualTo(2);
		assertThat(quantity(destination)).isEqualTo(3);
		assertThat(total(part.getId())).isEqualTo(5);
		assertThatThrownBy(() -> jdbc.update("UPDATE part_locations SET quantity = -1 WHERE id = ?", source))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void changeRejectsNegativeShelfAndOverflowWithoutMutation() {
		Company company = fixtures.company("Shelf change");
		Part part = fixtures.part(company, "Diode", 4, "A1", "B1");
		Long a1 = shelfId(part.getId(), "A1");
		Long b1 = shelfId(part.getId(), "B1");

		// B1 is empty: taking one unit fails even though the part total is 4.
		assertThatThrownBy(() -> change(company.getId(), part.getId(), b1, -1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> change(company.getId(), part.getId(), a1, Integer.MAX_VALUE))
			.isInstanceOf(ArithmeticException.class);
		assertThat(quantity(a1)).isEqualTo(4);
		assertThat(quantity(b1)).isZero();
		assertThat(total(part.getId())).isEqualTo(4);

		change(company.getId(), part.getId(), b1, 6);
		change(company.getId(), part.getId(), a1, -4);
		assertThat(quantity(a1)).isZero();
		assertThat(quantity(b1)).isEqualTo(6);
		assertThat(total(part.getId())).isEqualTo(6);
	}

	@Test
	void concurrentShelfChangesOnOnePartAllLand() throws Exception {
		Company company = fixtures.company("Shelf deltas");
		Part part = fixtures.part(company, "Inductor", 0, "A1", "B1");
		Long a1 = shelfId(part.getId(), "A1");
		Long b1 = shelfId(part.getId(), "B1");
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(4)) {
			var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
			for (int i = 0; i < 8; i++) {
				Long shelf = i % 2 == 0 ? a1 : b1;
				futures.add(pool.submit(() -> {
					start.await();
					change(company.getId(), part.getId(), shelf, 3);
					return null;
				}));
			}
			start.countDown();
			for (var future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		}
		assertThat(quantity(a1)).isEqualTo(12);
		assertThat(quantity(b1)).isEqualTo(12);
		assertThat(total(part.getId())).isEqualTo(24);
	}

	@Test
	void lockReturnsCurrentStockForAPartLoadedBeforeTheLock() {
		Company company = fixtures.company("Shelf stale read");
		Part part = fixtures.part(company, "Fuse", 5, "A1");
		Long a1 = shelfId(part.getId(), "A1");

		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			// Hydrate the part and its shelves before another transaction changes them.
			Part early = partRepository.findByIdAndCompanyId(part.getId(), company.getId()).orElseThrow();
			assertThat(early.getLocations().get(0).getQuantity()).isEqualTo(5);
			Thread writer = new Thread(() -> change(company.getId(), part.getId(), a1, 2));
			writer.start();
			try {
				writer.join(10_000);
			}
			catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
			Part locked = stock.lock(company.getId(), part.getId());
			assertThat(locked.getQuantity()).isEqualTo(7);
			stock.changeLocked(locked, stock.location(locked, company.getId(), a1), -1);
		});
		assertThat(quantity(a1)).isEqualTo(6);
		assertThat(total(part.getId())).isEqualTo(6);
	}

	private void change(Long companyId, Long partId, Long shelfId, int delta) {
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			Part locked = stock.lock(companyId, partId);
			stock.changeLocked(locked, stock.location(locked, companyId, shelfId), delta);
		});
	}

	private boolean move(Long companyId, Long partId, Long source, Long destination, int count,
			CountDownLatch start) {
		try {
			start.await();
			new TransactionTemplate(transactions).executeWithoutResult(status -> {
				Part locked = stock.lock(companyId, partId);
				stock.transferLocked(locked, stock.location(locked, companyId, source),
						stock.location(locked, companyId, destination), count);
			});
			return true;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private Long shelfId(Long partId, String name) {
		return jdbc.queryForObject("SELECT id FROM part_locations WHERE part_id = ? AND location = ?",
			Long.class, partId, name);
	}

	private int quantity(Long shelfId) {
		return jdbc.queryForObject("SELECT quantity FROM part_locations WHERE id = ?", Integer.class, shelfId);
	}

	private int total(Long partId) {
		return jdbc.queryForObject("SELECT quantity FROM parts WHERE id = ?", Integer.class, partId);
	}
}

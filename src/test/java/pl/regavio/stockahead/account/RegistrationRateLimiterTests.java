package pl.regavio.stockahead.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link RegistrationRateLimiter}'s sliding window and
 * reserve/release logic, driven by a mutable test clock.
 */
class RegistrationRateLimiterTests {

	private static final int LIMIT = 3;

	private static final Duration WINDOW = Duration.ofHours(1);

	private static final String IP = "203.0.113.7";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));

	private final RegistrationRateLimiter limiter = new RegistrationRateLimiter(LIMIT, WINDOW, clock);

	@Test
	void allowsUpToLimitWithinWindow() {
		for (int i = 0; i < LIMIT; i++) {
			assertThat(limiter.tryReserve(IP)).as("reservation %d", i + 1).isPresent();
			clock.advance(Duration.ofMinutes(1));
		}
	}

	@Test
	void rejectsOverLimit() {
		reserveAll(IP);

		assertThat(limiter.tryReserve(IP)).isEmpty();
		assertThat(limiter.tryReserve(IP)).as("a rejection does not free anything").isEmpty();
	}

	@Test
	void releasedReservationFreesSlot() {
		List<RegistrationRateLimiter.Reservation> reservations = reserveAll(IP);
		assertThat(limiter.tryReserve(IP)).isEmpty();

		limiter.release(reservations.get(1));

		assertThat(limiter.tryReserve(IP)).isPresent();
		assertThat(limiter.tryReserve(IP)).isEmpty();
	}

	@Test
	void releasingTheSameReservationTwiceFreesOnlyOneSlot() {
		List<RegistrationRateLimiter.Reservation> reservations = reserveAll(IP);

		limiter.release(reservations.get(0));
		limiter.release(reservations.get(0));

		assertThat(limiter.tryReserve(IP)).isPresent();
		assertThat(limiter.tryReserve(IP)).isEmpty();
	}

	@Test
	void slotFreesAfterWindowSlides() {
		limiter.tryReserve(IP).orElseThrow();
		clock.advance(Duration.ofMinutes(30));
		limiter.tryReserve(IP).orElseThrow();
		limiter.tryReserve(IP).orElseThrow();
		assertThat(limiter.tryReserve(IP)).isEmpty();

		clock.advance(Duration.ofMinutes(29));
		assertThat(limiter.tryReserve(IP)).as("oldest still inside the window").isEmpty();

		clock.advance(Duration.ofMinutes(1));
		assertThat(limiter.tryReserve(IP)).as("oldest aged out").isPresent();
		assertThat(limiter.tryReserve(IP)).as("only the oldest aged out").isEmpty();
	}

	@Test
	void differentIpsAreIndependent() {
		reserveAll(IP);

		assertThat(limiter.tryReserve(IP)).isEmpty();
		assertThat(limiter.tryReserve("198.51.100.1")).isPresent();
	}

	@Test
	void emptyIpEntriesArePruned() {
		RegistrationRateLimiter.Reservation released = limiter.tryReserve("198.51.100.1").orElseThrow();
		limiter.tryReserve("198.51.100.2").orElseThrow();
		assertThat(limiter.trackedIpCount()).isEqualTo(2);

		limiter.release(released);
		assertThat(limiter.trackedIpCount()).as("released IP removed").isEqualTo(1);

		clock.advance(WINDOW);
		limiter.tryReserve(IP).orElseThrow();
		assertThat(limiter.trackedIpCount()).as("aged-out IP removed").isEqualTo(1);
	}

	@Test
	void concurrentReservesNeverExceedLimit() throws Exception {
		int threads = 32;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Optional<RegistrationRateLimiter.Reservation>>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return limiter.tryReserve(IP);
				}));
			}
			start.countDown();

			int granted = 0;
			for (Future<Optional<RegistrationRateLimiter.Reservation>> result : results) {
				if (result.get(10, TimeUnit.SECONDS).isPresent()) {
					granted++;
				}
			}
			assertThat(granted).isEqualTo(LIMIT);
			assertThat(limiter.tryReserve(IP)).isEmpty();
		}
		finally {
			executor.shutdownNow();
		}
	}

	private List<RegistrationRateLimiter.Reservation> reserveAll(String ip) {
		List<RegistrationRateLimiter.Reservation> reservations = new ArrayList<>();
		for (int i = 0; i < LIMIT; i++) {
			reservations.add(limiter.tryReserve(ip).orElseThrow());
		}
		return reservations;
	}

	private static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}

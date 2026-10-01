package pl.regavio.stockahead.orders;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level coverage for {@link LockRetry#executeWithLockRetry(java.util.function.Supplier)}
 * that {@code ReservationConcurrencyTests}/{@code OrderCreationIntegrationTests} don't provide:
 * those prove the end invariant (no over-reservation) under real Postgres contention, but never
 * pin down that a retry actually fired, nor how many times. A regression narrowing the caught
 * exception type or changing the attempt bound could otherwise pass CI on a lucky run.
 */
class LockRetryTests {

	@Test
	void retriesUpToThreeTimesThenSucceeds() {
		AtomicInteger attempts = new AtomicInteger();
		LockRetry lockRetry = new LockRetry();

		Long result = lockRetry.executeWithLockRetry(() -> {
			if (attempts.incrementAndGet() < 3) {
				throw new CannotAcquireLockException("simulated lock contention");
			}
			return 42L;
		});

		assertThat(result).isEqualTo(42L);
		assertThat(attempts.get()).isEqualTo(3);
	}

	@Test
	void reThrowsLastFailureAfterExhaustingThreeAttempts() {
		AtomicInteger attempts = new AtomicInteger();
		LockRetry lockRetry = new LockRetry();

		assertThatThrownBy(() -> lockRetry.executeWithLockRetry(() -> {
			attempts.incrementAndGet();
			throw new CannotAcquireLockException("simulated lock contention, attempt " + attempts.get());
		})).isInstanceOf(CannotAcquireLockException.class)
			.hasMessageContaining("attempt 3");

		assertThat(attempts.get()).isEqualTo(3);
	}

}

package pl.regavio.stockahead.orders;

import java.util.function.Supplier;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Retries {@code action} up to {@link #MAX_LOCK_RETRY_ATTEMPTS} times when it
 * fails with {@link PessimisticLockingFailureException} (deadlock or
 * lock-timeout translation), with no backoff between attempts. Re-throws the
 * last failure once attempts are exhausted. Extracted from
 * {@code OrderController} so {@code PickingController} can share the same
 * retry behavior around its own {@code PartRepository.findByIdInForUpdate}
 * lock instead of duplicating the loop.
 */
@Component
class LockRetry {

	private static final int MAX_LOCK_RETRY_ATTEMPTS = 3;

	<T> T executeWithLockRetry(Supplier<T> action) {
		PessimisticLockingFailureException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_LOCK_RETRY_ATTEMPTS; attempt++) {
			try {
				return action.get();
			}
			catch (PessimisticLockingFailureException ex) {
				lastFailure = ex;
			}
		}
		throw lastFailure;
	}

}

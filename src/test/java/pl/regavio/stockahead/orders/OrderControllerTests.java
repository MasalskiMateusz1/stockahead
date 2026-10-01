package pl.regavio.stockahead.orders;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import pl.regavio.stockahead.projects.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level coverage for {@link OrderController#executeWithLockRetry(java.util.function.Supplier)}
 * that {@code ReservationConcurrencyTests}/{@code OrderCreationIntegrationTests} don't provide:
 * those prove the end invariant (no over-reservation) under real Postgres contention, but never
 * pin down that a retry actually fired, nor how many times. A regression narrowing the caught
 * exception type or changing the attempt bound could otherwise pass CI on a lucky run.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTests {

	@Mock
	private ProjectRepository projectRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ReservationAllocator reservationAllocator;

	@Mock
	private OrderDetailModel orderDetailModel;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private MessageSource messageSource;

	private OrderController newController() {
		return new OrderController(projectRepository, orderRepository, reservationAllocator, orderDetailModel,
				transactionManager, messageSource);
	}

	@Test
	void retriesUpToThreeTimesThenSucceeds() {
		AtomicInteger attempts = new AtomicInteger();
		OrderController controller = newController();

		Long result = controller.executeWithLockRetry(() -> {
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
		OrderController controller = newController();

		assertThatThrownBy(() -> controller.executeWithLockRetry(() -> {
			attempts.incrementAndGet();
			throw new CannotAcquireLockException("simulated lock contention, attempt " + attempts.get());
		})).isInstanceOf(CannotAcquireLockException.class)
			.hasMessageContaining("attempt 3");

		assertThat(attempts.get()).isEqualTo(3);
	}

}

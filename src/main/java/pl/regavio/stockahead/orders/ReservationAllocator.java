package pl.regavio.stockahead.orders;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;

/**
 * Recomputes every {@code OPEN} order's {@code OrderLine.reservedQuantity}
 * from scratch, following the priority (desc) -&gt; required date (asc) -&gt;
 * created-at/id (asc) allocation order from the PRD's Business Logic
 * section. Locks every {@link Part} row up front via
 * {@code PartRepository.findAllForUpdate()}'s {@code PESSIMISTIC_WRITE}, so
 * callers must invoke {@link #reallocateAll()} inside the same transaction
 * that creates or modifies the triggering row — the part locks and the order
 * writes then commit atomically together. Because every event fully rebuilds
 * the allocation from the current set of open orders, priority preemption (a
 * new high-priority order taking stock from an existing lower-priority one)
 * falls out for free, with no special-case "steal reservation" logic.
 */
@Component
class ReservationAllocator {

	static final Comparator<Order> ALLOCATION_ORDER = Comparator
		.comparing(Order::getPriority, Comparator.reverseOrder())
		.thenComparing(Order::getRequiredDate)
		.thenComparing(Order::getCreatedAt)
		.thenComparing(Order::getId);

	private final PartRepository partRepository;

	private final OrderRepository orderRepository;

	ReservationAllocator(PartRepository partRepository, OrderRepository orderRepository) {
		this.partRepository = partRepository;
		this.orderRepository = orderRepository;
	}

	/**
	 * Recomputes {@code reservedQuantity} for every line of every
	 * {@code OPEN} order, from a running per-part stock pool seeded with each
	 * part's current {@code quantity} (no picking exists yet to subtract).
	 * Orders are processed in allocation order; each order's lines are
	 * processed in their natural order, which is safe because
	 * {@code UNIQUE(order_id, part_id)} guarantees each line of an order
	 * touches a distinct part.
	 */
	void reallocateAll() {
		Map<Long, Integer> remainingStockByPartId = new HashMap<>();
		for (Part part : partRepository.findAllForUpdate()) {
			remainingStockByPartId.put(part.getId(), part.getQuantity());
		}

		List<Order> openOrders = orderRepository.findByStatus(OrderStatus.OPEN).stream()
			.sorted(ALLOCATION_ORDER)
			.toList();

		for (Order order : openOrders) {
			for (OrderLine line : order.getLines()) {
				Long partId = line.getPart().getId();
				int remaining = remainingStockByPartId.getOrDefault(partId, 0);
				int reserved = Math.min(remaining, line.getRequiredQuantity());
				line.setReservedQuantity(reserved);
				remainingStockByPartId.put(partId, remaining - reserved);
			}
		}
	}

}

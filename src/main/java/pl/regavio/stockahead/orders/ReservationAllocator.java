package pl.regavio.stockahead.orders;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;

/**
 * Recomputes {@code OrderLine.reservedQuantity} for the {@code OPEN} orders
 * touching a caller-supplied set of parts, following the priority (desc) -&gt;
 * required date (asc) -&gt; created-at/id (asc) allocation order from the
 * PRD's Business Logic section. Locks exactly the given {@link Part} ids via
 * {@code PartRepository.findByIdInForUpdate(ids)}'s {@code PESSIMISTIC_WRITE}
 * (ascending id order), so callers must invoke
 * {@link #reallocateForParts(Set)} inside the same transaction that creates
 * or modifies the triggering row, passing every part id their own
 * already-loaded writes touch — never derive the set from a separate query.
 * The part locks and the order writes then commit atomically together.
 * Because every event fully rebuilds the allocation for the affected parts
 * from the current set of open orders touching them, priority preemption (a
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

	private final OrderLineRepository orderLineRepository;

	ReservationAllocator(PartRepository partRepository, OrderLineRepository orderLineRepository) {
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
	}

	/**
	 * Recomputes {@code reservedQuantity} for every {@code OPEN}-order line
	 * touching one of {@code partIds}, from a running per-part stock pool
	 * seeded with each part's current {@code quantity} (no picking exists
	 * yet to subtract). Orders are processed in allocation order; each
	 * order's lines are processed in their natural order, which is safe
	 * because {@code UNIQUE(order_id, part_id)} guarantees each line of an
	 * order touches a distinct part.
	 */
	void reallocateForParts(Set<Long> partIds) {
		if (partIds.isEmpty()) {
			return;
		}
		Map<Long, Integer> remainingStockByPartId = new HashMap<>();
		for (Part part : partRepository.findByIdInForUpdate(partIds)) {
			remainingStockByPartId.put(part.getId(), part.getQuantity());
		}

		List<OrderLine> affectedLines = orderLineRepository.findOpenLinesForParts(partIds);
		Map<Long, Order> ordersById = affectedLines.stream()
			.collect(Collectors.toMap(l -> l.getOrder().getId(), OrderLine::getOrder, (a, b) -> a));
		Map<Long, List<OrderLine>> linesByOrderId = affectedLines.stream()
			.collect(Collectors.groupingBy(l -> l.getOrder().getId()));

		for (Order order : ordersById.values().stream().sorted(ALLOCATION_ORDER).toList()) {
			for (OrderLine line : linesByOrderId.get(order.getId())) {
				Long partId = line.getPart().getId();
				int remaining = remainingStockByPartId.getOrDefault(partId, 0);
				int reserved = Math.min(remaining, line.getRequiredQuantity());
				line.setReservedQuantity(reserved);
				remainingStockByPartId.put(partId, remaining - reserved);
			}
		}
	}

}

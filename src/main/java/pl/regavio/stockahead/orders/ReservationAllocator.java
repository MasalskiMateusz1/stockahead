package pl.regavio.stockahead.orders;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

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
 * Because every event fully rebuilds the allocation of the <em>non-taken</em>
 * open orders touching the affected parts, priority preemption (a new
 * high-priority order taking stock from an existing lower-priority one)
 * falls out for free, with no special-case "steal reservation" logic. A
 * taken order (one with a first pick already done) is <em>protected</em>,
 * not excluded: its current reservation is removed from the stock pool up
 * front and can never shrink, so no later, higher-priority competitor can
 * take it over. A taken order whose completion has not been reported still
 * joins the allocation pass in its normal allocation-order position, but
 * can only <em>grow</em>: it is topped up by what the pool has left, at
 * most to {@code requiredQuantity - pickedQuantity}. A taken order whose
 * completion has been reported (awaiting the manager's confirm/reject) keeps
 * its reservation and receives nothing extra, since it can no longer be
 * picked.
 */
@Component
public class ReservationAllocator {

	public static final Comparator<Order> ALLOCATION_ORDER = Comparator
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
	 * Recomputes {@code reservedQuantity} for the {@code OPEN} order lines
	 * touching one of {@code partIds}, from a running per-part stock pool
	 * seeded with each part's current {@code quantity} (picking may have
	 * already drawn it down), minus the current reservation of every taken
	 * order's lines on those parts (reported or not) — that floor leaves the
	 * pool before any order is processed, so no order can absorb units a
	 * taken order already holds. Then non-taken orders and taken orders whose
	 * completion is not reported are processed together in allocation order:
	 * a non-taken line is rebuilt as {@code min(pool, required)}; a taken
	 * line keeps its reservation and gains
	 * {@code min(pool, required - picked - reserved)}. Either way the pool
	 * shrinks by what was granted. Lines of a completion-reported order are
	 * never written. Each order's lines are processed in their natural order,
	 * which is safe because {@code UNIQUE(order_id, part_id)} guarantees each
	 * line of an order touches a distinct part.
	 */
	void reallocateForParts(Set<Long> partIds) {
		if (partIds.isEmpty()) {
			return;
		}
		// findByIdInForUpdate's own entities are NOT read from below: a caller may
		// already have a stale Part managed in this transaction's persistence context
		// (e.g. OrderController.create resolves BomLine.getPart() before this call),
		// in which case this query's own row data would be discarded in favor of
		// that already-managed, possibly outdated instance by Hibernate's identity
		// map. The lock it takes is still required; the quantities come from the
		// separate scalar read below, which always reflects the just-locked row.
		partRepository.findByIdInForUpdate(partIds);
		Map<Long, Integer> remainingStockByPartId = new HashMap<>();
		for (Object[] row : partRepository.findCurrentQuantities(partIds)) {
			remainingStockByPartId.put((Long) row[0], (Integer) row[1]);
		}

		List<OrderLine> affectedLines = orderLineRepository.findOpenLinesForParts(partIds);
		List<OrderLine> takenLines = affectedLines.stream().filter(line -> line.getOrder().isTaken()).toList();
		List<OrderLine> allocatableLines = affectedLines.stream()
			.filter(line -> !line.getOrder().isTaken() || !line.getOrder().isCompletionReported())
			.toList();

		for (OrderLine takenLine : takenLines) {
			remainingStockByPartId.merge(takenLine.getPart().getId(), -takenLine.getReservedQuantity(),
					Integer::sum);
		}
		remainingStockByPartId.replaceAll((partId, remaining) -> Math.max(0, remaining));

		Map<Long, Order> ordersById = allocatableLines.stream()
			.collect(Collectors.toMap(l -> l.getOrder().getId(), OrderLine::getOrder, (a, b) -> a));
		Map<Long, List<OrderLine>> linesByOrderId = allocatableLines.stream()
			.collect(Collectors.groupingBy(l -> l.getOrder().getId()));

		for (Order order : ordersById.values().stream().sorted(ALLOCATION_ORDER).toList()) {
			for (OrderLine line : linesByOrderId.get(order.getId())) {
				Long partId = line.getPart().getId();
				int remaining = remainingStockByPartId.getOrDefault(partId, 0);
				int granted;
				if (order.isTaken()) {
					// Top-up only: the current reservation already left the pool above
					// and is a floor; reservedQuantity is net of picks, so the unmet
					// remainder is required - picked - reserved (disjoint counters).
					int unmet = Math.max(0,
							line.getRequiredQuantity() - line.getPickedQuantity() - line.getReservedQuantity());
					granted = Math.min(remaining, unmet);
					line.setReservedQuantity(line.getReservedQuantity() + granted);
				}
				else {
					granted = Math.min(remaining, line.getRequiredQuantity());
					line.setReservedQuantity(granted);
				}
				remainingStockByPartId.put(partId, remaining - granted);
			}
		}
	}

}

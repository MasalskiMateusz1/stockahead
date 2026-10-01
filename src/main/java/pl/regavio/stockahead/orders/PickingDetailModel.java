package pl.regavio.stockahead.orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartLocation;

/**
 * Fills the {@code picking-detail} page model, reused by the {@code GET}
 * route and by a failed pick's re-render. Always re-reads inside its own
 * read-only transaction and copies the data into plain view records, never
 * reusing the caller's entities — same shape as {@code OrderDetailModel}/
 * {@code ProjectDetailModel}. Picking routes are open to both MANAGER and
 * TECHNICIAN, so unlike {@code OrderDetailModel} there is no role branch —
 * the page itself carries no role-gated content.
 */
@Component
class PickingDetailModel {

	static final String VIEW = "picking-detail";

	private final OrderRepository orderRepository;

	private final TransactionTemplate readTransaction;

	PickingDetailModel(OrderRepository orderRepository, PlatformTransactionManager transactionManager) {
		this.orderRepository = orderRepository;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Populates the detail page for {@code orderId}. An unknown order is 404.
	 * @return the {@code picking-detail} view name
	 */
	String render(Model model, Long orderId) {
		return render(model, orderId, null, null);
	}

	/**
	 * Same as {@link #render(Model, Long)}, with a page-level error message
	 * shown at the top of the page ({@code null} for none) and the id of the
	 * line whose pick form should show it.
	 */
	String render(Model model, Long orderId, String error, Long errorLineId) {
		DetailData data = readTransaction.execute(status -> load(orderId));
		model.addAttribute("orderId", orderId);
		model.addAttribute("order", data.order());
		model.addAttribute("lines", data.lines());
		if (error != null) {
			model.addAttribute("error", error);
			model.addAttribute("errorLineId", errorLineId);
		}
		return VIEW;
	}

	private DetailData load(Long orderId) {
		Order order = orderRepository.findByIdWithDetails(orderId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		List<LineView> lines = order.getLines().stream()
			.map(line -> new LineView(line.getId(), line.getPart().getName(), joinLocations(line.getPart()),
					line.getRequiredQuantity(), line.getReservedQuantity(), line.getPickedQuantity(),
					line.getReservedQuantity()))
			.sorted(Comparator.comparing(LineView::partName))
			.toList();
		OrderView orderView = new OrderView(order.getProject().getName(), order.getQuantityUnits(),
				order.getPriority(), order.getRequiredDate(), order.getStatus(), order.isTaken(),
				order.canReportCompletion(), order.isCompletionReported());
		return new DetailData(orderView, lines);
	}

	private static String joinLocations(Part part) {
		return part.getLocations().stream().map(PartLocation::getLocation).collect(Collectors.joining(", "));
	}

	record OrderView(String projectName, int quantityUnits, Priority priority, LocalDate requiredDate,
			OrderStatus status, boolean taken, boolean canReportCompletion, boolean completionReported) {
	}

	/**
	 * {@code remainingToPick} always equals {@code reservedQuantity}: every
	 * pick shifts its amount out of {@code reservedQuantity} into
	 * {@code pickedQuantity}, so {@code reservedQuantity} already nets out
	 * past picks. Kept as its own field to match the page's separate
	 * "remaining to pick" column.
	 */
	record LineView(Long lineId, String partName, String locations, int requiredQuantity, int reservedQuantity,
			int pickedQuantity, int remainingToPick) {
	}

	private record DetailData(OrderView order, List<LineView> lines) {
	}

}

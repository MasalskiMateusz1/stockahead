package pl.regavio.stockahead.orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fills the {@code orders-detail} page model. Always re-reads inside its own
 * read-only transaction and copies the data into plain view records, never
 * reusing the caller's entities — same shape as
 * {@code ProjectDetailModel}. Order routes are manager-only for every caller
 * that reaches them, so unlike {@code ProjectDetailModel} there is no
 * {@code isManager} branch.
 */
@Component
class OrderDetailModel {

	static final String VIEW = "orders-detail";

	private final OrderRepository orderRepository;

	private final TransactionTemplate readTransaction;

	private final OrderMoments orderMoments;

	OrderDetailModel(OrderRepository orderRepository, PlatformTransactionManager transactionManager,
			OrderMoments orderMoments) {
		this.orderRepository = orderRepository;
		this.orderMoments = orderMoments;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Populates the detail page for {@code orderId}. An unknown order is 404.
	 * @return the {@code orders-detail} view name
	 */
	String render(Model model, Long orderId) {
		return render(model, orderId, null);
	}

	/**
	 * Same as {@link #render(Model, Long)}, with a page-level error message
	 * shown at the top of the page ({@code null} for none) — used by a failed
	 * confirm/reject's re-render.
	 */
	String render(Model model, Long orderId, String error) {
		DetailData data = readTransaction.execute(status -> load(orderId));
		model.addAttribute("orderId", orderId);
		model.addAttribute("order", data.order());
		model.addAttribute("lines", data.lines());
		model.addAttribute("unmetLines", data.lines().stream()
			.filter(line -> line.pickedQuantity() < line.requiredQuantity())
			.toList());
		if (error != null) {
			model.addAttribute("error", error);
		}
		return VIEW;
	}

	private DetailData load(Long orderId) {
		Order order = orderRepository.findByIdWithDetails(orderId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		List<LineView> lines = order.getLines().stream()
			.map(line -> new LineView(line.getPart().getName(), line.getRequiredQuantity(),
					line.getReservedQuantity(), line.getMissingQuantity(),
					line.getPickedQuantity()))
			.sorted(Comparator.comparing(LineView::partName))
			.toList();
		String reportedByEmail = order.getCompletionReportedBy() == null ? null
				: order.getCompletionReportedBy().getEmail();
		OrderView orderView = new OrderView(order.getProject().getName(), order.getQuantityUnits(),
				order.getPriority(), order.getRequiredDate(), order.getStatus(), order.isCompletionReported(),
				orderMoments.format(order.getCompletionReportedAt()), reportedByEmail,
				orderMoments.format(order.getCompletedAt()));
		return new DetailData(orderView, lines);
	}

	record OrderView(String projectName, int quantityUnits, Priority priority, LocalDate requiredDate,
			OrderStatus status, boolean completionReported, String reportedAt, String reportedByEmail,
			String completedAt) {
	}

	/**
	 * {@code missingQuantity} is the shortfall neither reserved nor picked
	 * (what the shopping list counts); unmet quantity for the completion warning is
	 * {@code pickedQuantity < requiredQuantity}, since
	 * {@code reservedQuantity} is live and already nets out past picks.
	 */
	record LineView(String partName, int requiredQuantity, int reservedQuantity, int missingQuantity,
			int pickedQuantity) {
	}

	private record DetailData(OrderView order, List<LineView> lines) {
	}

}

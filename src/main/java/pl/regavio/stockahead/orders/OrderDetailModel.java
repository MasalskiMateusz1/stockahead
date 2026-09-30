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

	OrderDetailModel(OrderRepository orderRepository, PlatformTransactionManager transactionManager) {
		this.orderRepository = orderRepository;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Populates the detail page for {@code orderId}. An unknown order is 404.
	 * @return the {@code orders-detail} view name
	 */
	String render(Model model, Long orderId) {
		DetailData data = readTransaction.execute(status -> load(orderId));
		model.addAttribute("order", data.order());
		model.addAttribute("lines", data.lines());
		return VIEW;
	}

	private DetailData load(Long orderId) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		List<LineView> lines = order.getLines().stream()
			.map(line -> new LineView(line.getPart().getName(), line.getRequiredQuantity(),
					line.getReservedQuantity(), line.getRequiredQuantity() - line.getReservedQuantity()))
			.sorted(Comparator.comparing(LineView::partName))
			.toList();
		OrderView orderView = new OrderView(order.getProject().getName(), order.getQuantityUnits(),
				order.getPriority(), order.getRequiredDate());
		return new DetailData(orderView, lines);
	}

	record OrderView(String projectName, int quantityUnits, Priority priority, LocalDate requiredDate) {
	}

	record LineView(String partName, int requiredQuantity, int reservedQuantity, int missingQuantity) {
	}

	private record DetailData(OrderView order, List<LineView> lines) {
	}

}

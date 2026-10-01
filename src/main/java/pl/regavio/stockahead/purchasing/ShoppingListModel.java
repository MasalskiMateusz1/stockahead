package pl.regavio.stockahead.purchasing;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;

import pl.regavio.stockahead.orders.OrderLine;
import pl.regavio.stockahead.orders.OrderLineRepository;
import pl.regavio.stockahead.orders.ReservationAllocator;
import pl.regavio.stockahead.parts.Part;

/**
 * Fills the {@code purchasing-list} page model and backs the CSV export with
 * the same read path. Groups every open order line with a shortage
 * ({@code requiredQuantity > reservedQuantity}) by part, one aggregated row
 * per part, sorted by part name. Always re-reads inside its own read-only
 * transaction and copies the data into plain view records, never reusing the
 * caller's entities — same shape as {@code OrderDetailModel}.
 */
@Component
class ShoppingListModel {

	private final OrderLineRepository orderLineRepository;

	private final TransactionTemplate readTransaction;

	ShoppingListModel(OrderLineRepository orderLineRepository, PlatformTransactionManager transactionManager) {
		this.orderLineRepository = orderLineRepository;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Populates the shopping list page.
	 * @return the {@code purchasing-list} view name
	 */
	String render(Model model) {
		List<ShoppingListRow> rows = readTransaction.execute(status -> buildRows());
		model.addAttribute("rows", rows);
		return "purchasing-list";
	}

	/**
	 * Same rows as {@link #render(Model)}, for the CSV export.
	 */
	List<ShoppingListRow> rowsForExport() {
		return readTransaction.execute(status -> buildRows());
	}

	private List<ShoppingListRow> buildRows() {
		List<OrderLine> shortageLines = orderLineRepository.findOpenLinesWithShortage();
		Map<Long, List<OrderLine>> linesByPartId = shortageLines.stream()
			.collect(Collectors.groupingBy(line -> line.getPart().getId()));

		return linesByPartId.values().stream().map(this::toRow)
			.sorted(Comparator.comparing(ShoppingListRow::partName))
			.toList();
	}

	private ShoppingListRow toRow(List<OrderLine> linesForPart) {
		Part part = linesForPart.get(0).getPart();
		int missingQuantity = linesForPart.stream()
			.mapToInt(line -> line.getRequiredQuantity() - line.getReservedQuantity())
			.sum();
		List<BlockedOrderView> blockedOrders = linesForPart.stream()
			.sorted(Comparator.comparing(OrderLine::getOrder, ReservationAllocator.ALLOCATION_ORDER))
			.map(line -> new BlockedOrderView(line.getOrder().getId(), line.getOrder().getProject().getName(),
					line.getOrder().getRequiredDate(), line.getRequiredQuantity() - line.getReservedQuantity()))
			.toList();
		return new ShoppingListRow(part.getName(), part.isActive(), missingQuantity, blockedOrders);
	}

	record ShoppingListRow(String partName, boolean partActive, int missingQuantity,
			List<BlockedOrderView> blockedOrders) {
	}

	record BlockedOrderView(Long orderId, String projectName, LocalDate requiredDate, int missingQuantity) {
	}

}

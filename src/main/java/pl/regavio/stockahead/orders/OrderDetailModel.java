package pl.regavio.stockahead.orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartLocation;
import pl.regavio.stockahead.parts.PartRepository;

/**
 * Fills the {@code orders-detail} page model, and the {@code orders-cancel}
 * confirmation page model. Always re-reads inside its own
 * read-only transaction and copies the data into plain view records, never
 * reusing the caller's entities — same shape as
 * {@code ProjectDetailModel}. Order routes are manager-only for every caller
 * that reaches them, so unlike {@code ProjectDetailModel} there is no
 * {@code isManager} branch.
 */
@Component
class OrderDetailModel {

	static final String VIEW = "orders-detail";

	static final String CANCEL_VIEW = "orders-cancel";

	private final OrderRepository orderRepository;

	private final PartRepository partRepository;

	private final TransactionTemplate readTransaction;

	private final OrderMoments orderMoments;

	private final AccountRepository accountRepository;

	OrderDetailModel(OrderRepository orderRepository, PartRepository partRepository,
			PlatformTransactionManager transactionManager, OrderMoments orderMoments,
			AccountRepository accountRepository) {
		this.orderRepository = orderRepository;
		this.partRepository = partRepository;
		this.orderMoments = orderMoments;
		this.accountRepository = accountRepository;
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
		return render(model, orderId, error, null, null);
	}

	/**
	 * Same as {@link #render(Model, Long, String)}, keeping the priority and
	 * required date a failed change submitted in the change form ({@code null}
	 * for the order's current values). The reassign picker lists the
	 * assignable accounts with the current assignee preselected; a deactivated
	 * assignee is not listed, so nothing is preselected for one.
	 */
	String render(Model model, Long orderId, String error, String submittedPriority, String submittedRequiredDate) {
		DetailData data = readTransaction.execute(status -> load(orderId));
		OrderView order = data.order();
		model.addAttribute("orderId", orderId);
		model.addAttribute("order", order);
		model.addAttribute("changePriority",
				submittedPriority != null ? submittedPriority : order.priority().name());
		model.addAttribute("changeRequiredDate",
				submittedRequiredDate != null ? submittedRequiredDate : order.requiredDate().toString());
		// A past-due order may keep its current date, so the picker must allow it.
		LocalDate today = LocalDate.now();
		model.addAttribute("changeMinDate",
				(order.requiredDate().isBefore(today) ? order.requiredDate() : today).toString());
		model.addAttribute("assignableAccounts", accountRepository.findAssignable());
		model.addAttribute("assigneeId", data.assigneeId());
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
					line.getPickedQuantity(), line.getReturnedQuantity()))
			.sorted(Comparator.comparing(LineView::partName))
			.toList();
		String reportedByEmail = order.getCompletionReportedBy() == null ? null
				: order.getCompletionReportedBy().getEmail();
		String cancelledByEmail = order.getCancelledBy() == null ? null : order.getCancelledBy().getEmail();
		Account assignee = order.getAssignee();
		OrderView orderView = new OrderView(order.getProject().getName(), order.getQuantityUnits(),
				order.getPriority(), order.getRequiredDate(), order.getStatus(), order.isCompletionReported(),
				orderMoments.format(order.getCompletionReportedAt()), reportedByEmail,
				orderMoments.format(order.getCompletedAt()), order.isTaken(),
				orderMoments.format(order.getCancelledAt()), cancelledByEmail, order.canCancel(),
				order.getBuiltUnits(), assignee == null ? null : assignee.getEmail(),
				assignee != null && assignee.isActive());
		return new DetailData(orderView, lines, assignee == null ? null : assignee.getId());
	}

	/**
	 * Loads the cancel page's data for {@code orderId} in its own read-only
	 * transaction. An unknown order is 404.
	 */
	CancelPage loadCancel(Long orderId) {
		return readTransaction.execute(status -> {
			Order order = orderRepository.findByIdWithDetails(orderId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			List<OrderLine> picked = order.getLines().stream().filter(line -> line.getPickedQuantity() > 0).toList();
			// Order.lines and Part.locations are both bags, which Hibernate cannot fetch in one query;
			// one extra query fills every shown part's locations instead of one lazy load per line.
			partRepository.findWithLocationsByIdIn(picked.stream().map(line -> line.getPart().getId()).toList());
			List<CancelLineView> lines = picked.stream()
				.map(line -> new CancelLineView(line.getId(), line.getPart().getName(),
						joinLocations(line.getPart()), line.getPickedQuantity(),
						Integer.toString(line.getPickedQuantity())))
				.sorted(Comparator.comparing(CancelLineView::partName))
				.toList();
			return new CancelPage(order.getProject().getName(), order.canCancel(), lines);
		});
	}

	/**
	 * Populates the cancel page for {@code orderId}, re-read fresh, with a
	 * page-level error ({@code null} for none). Each shown line's return field
	 * keeps the raw value a failed cancel submitted for it (by line id), or is
	 * prefilled with the line's picked amount; the hidden seen-picked value is
	 * always the current one, so a resubmission after a stale-form error
	 * compares against what the page now shows.
	 * @return the {@code orders-cancel} view name
	 */
	String renderCancel(Model model, Long orderId, String error, Map<Long, String> submittedReturns) {
		return renderCancel(model, orderId, loadCancel(orderId), error, submittedReturns);
	}

	/**
	 * Same as {@link #renderCancel(Model, Long, String, Map)}, with the page
	 * data already loaded by {@link #loadCancel(Long)}.
	 */
	String renderCancel(Model model, Long orderId, CancelPage page, String error,
			Map<Long, String> submittedReturns) {
		List<CancelLineView> lines = page.lines().stream()
			.map(line -> submittedReturns.containsKey(line.lineId())
					? new CancelLineView(line.lineId(), line.partName(), line.locations(), line.pickedQuantity(),
							submittedReturns.get(line.lineId()))
					: line)
			.toList();
		model.addAttribute("orderId", orderId);
		model.addAttribute("projectName", page.projectName());
		model.addAttribute("cancellable", page.cancellable());
		model.addAttribute("lines", lines);
		if (error != null) {
			model.addAttribute("error", error);
		}
		return CANCEL_VIEW;
	}

	private static String joinLocations(Part part) {
		return part.getLocations().stream().map(PartLocation::getLocation).collect(Collectors.joining(", "));
	}

	/**
	 * {@code changeable} gates the priority/date change form: only an
	 * {@code OPEN} order nobody has picked from yet; {@code canCancel} gates
	 * the cancel link ({@code OPEN} and not reported). {@code builtUnits} is
	 * the reported count of units built, {@code null} when no report is
	 * pending or the report predates the count. {@code assigneeEmail} is who
	 * the order is for, {@code null} when unassigned; {@code assigneeActive}
	 * is {@code false} for a deactivated assignee (and when unassigned).
	 */
	record OrderView(String projectName, int quantityUnits, Priority priority, LocalDate requiredDate,
			OrderStatus status, boolean completionReported, String reportedAt, String reportedByEmail,
			String completedAt, boolean taken, String cancelledAt, String cancelledByEmail, boolean canCancel,
			Integer builtUnits, String assigneeEmail, boolean assigneeActive) {

		public boolean changeable() {
			return status == OrderStatus.OPEN && !taken;
		}

		/**
		 * Whether a count was reported and it is below {@code quantityUnits};
		 * {@code false} for an unknown ({@code null}) count.
		 */
		public boolean partiallyCompleted() {
			return builtUnits != null && builtUnits < quantityUnits;
		}

	}

	/**
	 * {@code missingQuantity} is the shortfall neither reserved nor picked
	 * (what the shopping list counts); unmet quantity for the completion warning is
	 * {@code pickedQuantity < requiredQuantity}, since
	 * {@code reservedQuantity} is live and already nets out past picks.
	 * {@code returnedQuantity} is what a cancel put back into stock.
	 */
	record LineView(String partName, int requiredQuantity, int reservedQuantity, int missingQuantity,
			int pickedQuantity, int returnedQuantity) {
	}

	/**
	 * The cancel page: the order's lines with something picked, and whether
	 * the order can still be cancelled.
	 */
	record CancelPage(String projectName, boolean cancellable, List<CancelLineView> lines) {
	}

	/**
	 * One picked line on the cancel page. {@code pickedQuantity} is cumulative
	 * ({@code reservedQuantity} is live), so it alone is the returnable
	 * amount; {@code returnValue} is the raw text shown in the return field.
	 */
	record CancelLineView(Long lineId, String partName, String locations, int pickedQuantity,
			String returnValue) {
	}

	/**
	 * {@code assigneeId} is the current assignee's id, {@code null} when
	 * unassigned.
	 */
	private record DetailData(OrderView order, List<LineView> lines, Long assigneeId) {
	}

}

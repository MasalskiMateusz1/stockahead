package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Emails;
import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;
import pl.regavio.stockahead.projects.BomLine;
import pl.regavio.stockahead.projects.Project;
import pl.regavio.stockahead.projects.ProjectRepository;

/**
 * Manager-only order creation: a form picking an active project, a unit
 * quantity, a priority, and a required date, and the {@code POST} that
 * snapshots the project's BOM into {@link OrderLine}s and reserves stock for
 * them via {@link ReservationAllocator} in the same transaction. An unknown
 * or inactive {@code projectId} mirrors
 * {@code ProjectBomController.addLine}'s {@code part == null} pattern: the
 * check happens inside the transaction and renders a friendly error rather
 * than 404, since the raw id came from a dropdown of active projects that
 * could have changed between page load and submit. An unrecognized priority
 * falls back to {@code NORMAL} rather than failing the submission, since the
 * form only ever offers the three valid values.
 * <p>
 * Also hosts the manager's side of the completion workflow: confirming a
 * reported order ({@code COMPLETED}, its unpicked reservations released and
 * reallocated to the open orders in allocation order, including short taken
 * ones) or rejecting the report (back to normal picking, with its parts
 * reallocated so it catches up). Both follow {@code PickingController.reportCompletion}'s
 * scalar-lookup-then-lock-then-re-read shape for the same open-in-view
 * stale-cache reason documented there.
 * <p>
 * The same shape backs changing the priority and required date of an
 * untaken order, which reallocates its parts so the new schedule takes
 * effect at once, and cancelling an {@code OPEN}, unreported order with the
 * picked units the manager hands back returned to stock.
 */
@Controller
public class OrderController {

	private static final int MAX_QUANTITY_UNITS = 1_000_000;

	private static final String RETURNED_PARAM_PREFIX = "returned_";

	private static final String SEEN_PICKED_PARAM_PREFIX = "seenPicked_";

	private final ProjectRepository projectRepository;

	private final OrderRepository orderRepository;

	private final OrderLineRepository orderLineRepository;

	private final PartRepository partRepository;

	private final ReservationAllocator reservationAllocator;

	private final OrderDetailModel orderDetailModel;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	private final AccountRepository accountRepository;

	private final MessageSource messageSource;

	OrderController(ProjectRepository projectRepository, OrderRepository orderRepository,
			OrderLineRepository orderLineRepository, PartRepository partRepository,
			ReservationAllocator reservationAllocator, OrderDetailModel orderDetailModel, LockRetry lockRetry,
			PlatformTransactionManager transactionManager, AccountRepository accountRepository,
			MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.orderRepository = orderRepository;
		this.orderLineRepository = orderLineRepository;
		this.partRepository = partRepository;
		this.reservationAllocator = reservationAllocator;
		this.orderDetailModel = orderDetailModel;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.accountRepository = accountRepository;
		this.messageSource = messageSource;
	}

	@GetMapping("/orders")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(Model model) {
		List<Order> openOrders = orderRepository.findByStatusWithProject(OrderStatus.OPEN).stream()
			.sorted(ReservationAllocator.ALLOCATION_ORDER)
			.toList();
		model.addAttribute("pendingOrders", openOrders.stream().filter(Order::isCompletionReported).toList());
		model.addAttribute("orders", openOrders.stream().filter(order -> !order.isCompletionReported()).toList());
		return "orders-list";
	}

	@GetMapping("/orders/{id}")
	@PreAuthorize("hasRole('MANAGER')")
	public String detail(@PathVariable Long id, Model model) {
		return orderDetailModel.render(model, id);
	}

	/**
	 * Confirms a pending completion report: the order becomes
	 * {@code COMPLETED}, every line's unpicked {@code reservedQuantity} is
	 * released, and the freed stock is reallocated to the open orders on those
	 * parts in allocation order, including short taken ones. The order is flushed as {@code COMPLETED} with
	 * zeroed reservations BEFORE {@link ReservationAllocator#reallocateForParts}
	 * runs — otherwise the still-{@code OPEN}, taken order's frozen reservation
	 * would be subtracted from the pool and the released units would never
	 * reach the other orders.
	 */
	@PostMapping("/orders/{id}/confirm-completion")
	@PreAuthorize("hasRole('MANAGER')")
	public String confirmCompletion(@PathVariable Long id, Model model, Locale locale) {
		return transitionReportedOrder(id, model, locale, (order, partIds) -> {
			order.setStatus(OrderStatus.COMPLETED);
			order.setCompletedAt(Instant.now());
			for (OrderLine line : order.getLines()) {
				line.setReservedQuantity(0);
			}
			orderRepository.saveAndFlush(order);
			reservationAllocator.reallocateForParts(partIds);
		});
	}

	/**
	 * Rejects a pending completion report, clearing
	 * {@code completionReportedAt}/{@code completionReportedBy} so the order
	 * goes back to normal picking, then reallocates the order's parts so it
	 * immediately catches up on units it missed while reported (a reported
	 * order receives no top-up). The report is cleared and flushed BEFORE
	 * {@link ReservationAllocator#reallocateForParts} runs — otherwise the
	 * allocator would still see the order as reported and skip it. Its
	 * existing reservation is never reduced.
	 */
	@PostMapping("/orders/{id}/reject-completion")
	@PreAuthorize("hasRole('MANAGER')")
	public String rejectCompletion(@PathVariable Long id, Model model, Locale locale) {
		return transitionReportedOrder(id, model, locale, (order, partIds) -> {
			order.setCompletionReportedAt(null);
			order.setCompletionReportedBy(null);
			orderRepository.saveAndFlush(order);
			reservationAllocator.reallocateForParts(partIds);
		});
	}

	/**
	 * Changes the priority and required date of an {@code OPEN}, untaken
	 * order (FR-021), then reallocates its parts so the new schedule reorders
	 * the allocation at once: a raised priority can take unpicked reservations
	 * from lower untaken orders, a lowered one gives them back. Taken orders
	 * are never affected, since the allocator keeps their reservation as a
	 * floor. The priority must be one of the three values (no create-style
	 * fallback). The date must be a valid ISO date not before today, unless it
	 * equals the order's current required date, compared against the value
	 * re-read under the lock, so a past-due order can still be re-prioritized.
	 * The order is flushed BEFORE {@link ReservationAllocator#reallocateForParts}
	 * runs, which reads its schedule through the allocation order.
	 */
	@PostMapping("/orders/{id}/change")
	@PreAuthorize("hasRole('MANAGER')")
	public String change(@PathVariable Long id,
			@RequestParam(required = false) String priority,
			@RequestParam(required = false) String requiredDate,
			Model model,
			Locale locale) {
		Function<String, String> renderError = error -> orderDetailModel.render(model, id, error, priority,
				requiredDate);

		Priority parsedPriority = parseStrictPriority(priority);
		if (parsedPriority == null) {
			return renderError.apply(messageSource.getMessage("orders.error.priorityInvalid", null, locale));
		}
		LocalDate parsedRequiredDate = parseDate(requiredDate);
		if (parsedRequiredDate == null) {
			return renderError.apply(messageSource.getMessage("orders.error.dateRequired", null, locale));
		}

		return transitionOrder(id, locale, "orders.error.changeFailed", renderError, "redirect:/orders/" + id,
				(order, partIds) -> {
					if (order.getStatus() != OrderStatus.OPEN || order.isTaken()) {
						return messageSource.getMessage("orders.error.notChangeable", null, locale);
					}
					if (parsedRequiredDate.isBefore(LocalDate.now())
							&& !parsedRequiredDate.equals(order.getRequiredDate())) {
						return messageSource.getMessage("orders.error.datePast", null, locale);
					}
					order.setPriority(parsedPriority);
					order.setRequiredDate(parsedRequiredDate);
					orderRepository.saveAndFlush(order);
					reservationAllocator.reallocateForParts(partIds);
					return null;
				});
	}

	/**
	 * The cancel confirmation page (FR-011): each line with picked units, its
	 * return field prefilled with the picked amount. An order that can no
	 * longer be cancelled goes back to its detail page.
	 */
	@GetMapping("/orders/{id}/cancel")
	@PreAuthorize("hasRole('MANAGER')")
	public String cancelForm(@PathVariable Long id, Model model) {
		OrderDetailModel.CancelPage page = orderDetailModel.loadCancel(id);
		if (!page.cancellable()) {
			return "redirect:/orders/" + id;
		}
		return orderDetailModel.renderCancel(model, id, page, null, Map.of());
	}

	/**
	 * Cancels an {@code OPEN}, unreported order (FR-011). Each line the form
	 * showed posts {@code returned_<lineId>} (picked units going back into
	 * stock, the rest count as used) and {@code seenPicked_<lineId>} (the
	 * picked amount the form displayed); a line it did not show counts as seen
	 * with 0 picked. The return format is checked before the lock; under the
	 * lock the order is re-checked, the form is rejected as stale when any
	 * line's current {@code pickedQuantity} differs from the seen one (a pick
	 * committed after the page loaded must not silently become "used"), and
	 * each return is bounded by the line's cumulative {@code pickedQuantity}
	 * alone ({@code reservedQuantity} is live, so it is not subtracted). Every
	 * check runs before anything is mutated. Then: returns go into
	 * {@code parts.quantity}, reservations are zeroed, the order becomes
	 * {@code CANCELLED} with {@code cancelledAt}/{@code cancelledBy}, and the
	 * order and parts are flushed BEFORE
	 * {@link ReservationAllocator#reallocateForParts} runs — otherwise the
	 * allocator would still see the order {@code OPEN} (subtracting a taken
	 * order's reservation from the pool) and stale stock, and neither the freed
	 * nor the returned units would reach the other open orders.
	 */
	@PostMapping("/orders/{id}/cancel")
	@PreAuthorize("hasRole('MANAGER')")
	public String cancel(@PathVariable Long id, @RequestParam Map<String, String> params,
			Authentication authentication, Model model, Locale locale) {
		Map<Long, String> rawReturns = paramsByLineId(params, RETURNED_PARAM_PREFIX);
		Map<Long, String> rawSeenPicked = paramsByLineId(params, SEEN_PICKED_PARAM_PREFIX);
		Function<String, String> renderError = error -> orderDetailModel.renderCancel(model, id, error,
				rawReturns);

		Map<Long, Integer> returns = new HashMap<>();
		for (Map.Entry<Long, String> entry : rawReturns.entrySet()) {
			Integer parsed = parseNonNegativeInt(entry.getValue());
			if (parsed == null) {
				return renderError.apply(messageSource.getMessage("orders.error.returnNotInteger", null, locale));
			}
			returns.put(entry.getKey(), parsed);
		}
		Map<Long, Integer> seenPicked = new HashMap<>();
		for (Map.Entry<Long, String> entry : rawSeenPicked.entrySet()) {
			if (!returns.containsKey(entry.getKey())) {
				// A shown line without its return field: missing is an error, not 0.
				return renderError.apply(messageSource.getMessage("orders.error.returnNotInteger", null, locale));
			}
			Integer parsed = parseNonNegativeInt(entry.getValue());
			if (parsed == null) {
				return renderError.apply(messageSource.getMessage("orders.error.pickedChanged", null, locale));
			}
			seenPicked.put(entry.getKey(), parsed);
		}

		return transitionOrder(id, locale, "orders.error.cancelFailed", renderError, "redirect:/orders/" + id,
				(order, partIds) -> {
					if (!order.canCancel()) {
						return messageSource.getMessage("orders.error.notCancellable", null, locale);
					}
					for (OrderLine line : order.getLines()) {
						if (line.getPickedQuantity() != seenPicked.getOrDefault(line.getId(), 0)) {
							return messageSource.getMessage("orders.error.pickedChanged", null, locale);
						}
					}
					for (OrderLine line : order.getLines()) {
						Integer returned = returns.get(line.getId());
						if (returned == null && line.getPickedQuantity() > 0) {
							return messageSource.getMessage("orders.error.returnNotInteger", null, locale);
						}
						if (returned != null && returned > line.getPickedQuantity()) {
							return messageSource.getMessage("orders.error.returnExceedsPicked",
									new Object[] { line.getPickedQuantity(), line.getPart().getName() }, locale);
						}
					}

					Account canceller = accountRepository
						.findByCanonicalEmail(Emails.canonical(authentication.getName()))
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
					for (OrderLine line : order.getLines()) {
						int returned = returns.getOrDefault(line.getId(), 0);
						Part part = line.getPart();
						part.setQuantity(part.getQuantity() + returned);
						line.setReturnedQuantity(returned);
					}
					for (OrderLine line : order.getLines()) {
						line.setReservedQuantity(0);
					}
					order.setStatus(OrderStatus.CANCELLED);
					order.setCancelledAt(Instant.now());
					order.setCancelledBy(canceller);
					// Flushes the whole persistence context: the order, its lines and the
					// locked parts, so the allocator reads the returned stock.
					orderRepository.saveAndFlush(order);
					reservationAllocator.reallocateForParts(partIds);
					return null;
				});
	}

	/**
	 * Collects the request params named {@code <prefix><lineId>} by line id;
	 * a param whose suffix is not a line id is ignored.
	 */
	private static Map<Long, String> paramsByLineId(Map<String, String> params, String prefix) {
		Map<Long, String> byLineId = new HashMap<>();
		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}
			try {
				byLineId.put(Long.valueOf(entry.getKey().substring(prefix.length())), entry.getValue());
			}
			catch (NumberFormatException ex) {
				// Not one of ours.
			}
		}
		return byLineId;
	}

	/**
	 * Parses an integer of at least 0, or {@code null} when missing,
	 * malformed, negative or out of {@code int} range.
	 */
	private static Integer parseNonNegativeInt(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(raw.trim());
			return parsed < 0 ? null : parsed;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * Confirm/reject's precondition on top of {@link #transitionOrder}: the
	 * order must still be {@code OPEN} and reported. Failures re-render the
	 * detail page; success goes back to the order list.
	 */
	private String transitionReportedOrder(Long orderId, Model model, Locale locale,
			BiConsumer<Order, Set<Long>> transition) {
		return transitionOrder(orderId, locale, "orders.error.completionFailed",
				error -> orderDetailModel.render(model, orderId, error), "redirect:/orders", (order, partIds) -> {
					if (order.getStatus() != OrderStatus.OPEN || !order.isCompletionReported()) {
						return messageSource.getMessage("orders.error.notReported", null, locale);
					}
					transition.accept(order, partIds);
					return null;
				});
	}

	/**
	 * Shared lock/re-check shape for the manager's order transitions: a
	 * scalar pre-lock part-id lookup (never {@code Order.getLines()} before
	 * the lock — see {@code PickingController}), the same part-row locks a
	 * pick takes, and a fresh re-read of the order handed to
	 * {@code transition} (with the locked part ids), which re-checks its
	 * precondition and returns a localized business error, or {@code null}
	 * after applying the change. An unknown order is 404; a business error or
	 * a DB/lock failure ({@code failureKey}) goes to {@code renderError}, and
	 * success returns {@code successView}.
	 */
	private String transitionOrder(Long orderId, Locale locale, String failureKey,
			Function<String, String> renderError, String successView, OrderTransition transition) {
		Set<Long> partIds = transactionTemplate
			.execute(status -> new HashSet<>(orderLineRepository.findPartIdsForOrder(orderId)));

		String businessError;
		try {
			businessError = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				if (!partIds.isEmpty()) {
					partRepository.findByIdInForUpdate(partIds);
				}
				Order order = orderRepository.findById(orderId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				return transition.apply(order, partIds);
			}));
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			return renderError.apply(messageSource.getMessage(failureKey, null, locale));
		}

		if (businessError != null) {
			return renderError.apply(businessError);
		}

		return successView;
	}

	/**
	 * A transition applied under the part locks to a freshly re-read order:
	 * returns a localized business error (nothing changed), or {@code null}
	 * once applied.
	 */
	@FunctionalInterface
	private interface OrderTransition {

		String apply(Order order, Set<Long> partIds);

	}

	@GetMapping("/orders/new")
	@PreAuthorize("hasRole('MANAGER')")
	public String newForm(Model model) {
		populateFormModel(model);
		return "orders-new";
	}

	@PostMapping("/orders")
	@PreAuthorize("hasRole('MANAGER')")
	public String create(
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String quantityUnits,
			@RequestParam(required = false) String priority,
			@RequestParam(required = false) String requiredDate,
			Model model,
			Locale locale) {
		String quantityError = validateQuantityUnits(quantityUnits, locale);
		if (quantityError != null) {
			return renderNewOrderError(model, quantityError, projectId, quantityUnits, priority, requiredDate);
		}
		int parsedQuantityUnits = Integer.parseInt(quantityUnits.trim());

		String dateError = validateRequiredDate(requiredDate, locale);
		if (dateError != null) {
			return renderNewOrderError(model, dateError, projectId, quantityUnits, priority, requiredDate);
		}
		LocalDate parsedRequiredDate = LocalDate.parse(requiredDate.trim());

		Long parsedProjectId = parseProjectId(projectId);
		if (parsedProjectId == null) {
			return renderNewOrderError(model,
					messageSource.getMessage("orders.error.projectUnavailable", null, locale), projectId,
					quantityUnits, priority, requiredDate);
		}

		Priority parsedPriority = parsePriority(priority);

		Long newOrderId;
		try {
			newOrderId = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				Project project = projectRepository.findById(parsedProjectId).filter(Project::isActive).orElse(null);
				if (project == null) {
					return null;
				}
				Order order = new Order();
				order.setProject(project);
				order.setQuantityUnits(parsedQuantityUnits);
				order.setPriority(parsedPriority);
				order.setRequiredDate(parsedRequiredDate);
				order.setCreatedAt(Instant.now());
				for (BomLine bomLine : project.getBomLines()) {
					OrderLine line = new OrderLine();
					line.setPart(bomLine.getPart());
					line.setRequiredQuantity(bomLine.getQuantityPerUnit() * parsedQuantityUnits);
					line.setReservedQuantity(0);
					order.addLine(line);
				}
				Order saved = orderRepository.saveAndFlush(order);
				Set<Long> affectedPartIds = order.getLines().stream()
					.map(line -> line.getPart().getId())
					.collect(Collectors.toSet());
				reservationAllocator.reallocateForParts(affectedPartIds);
				return saved.getId();
			}));
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			return renderNewOrderError(model, messageSource.getMessage("orders.error.saveFailed", null, locale),
					projectId, quantityUnits, priority, requiredDate);
		}

		if (newOrderId == null) {
			return renderNewOrderError(model,
					messageSource.getMessage("orders.error.projectUnavailable", null, locale), projectId,
					quantityUnits, priority, requiredDate);
		}

		return "redirect:/orders/" + newOrderId;
	}

	/**
	 * Returns the localized error for a raw quantity-units value, or
	 * {@code null} when it parses to an integer of at least 1.
	 */
	private String validateQuantityUnits(String rawQuantityUnits, Locale locale) {
		int parsed;
		try {
			parsed = Integer.parseInt(rawQuantityUnits == null ? "" : rawQuantityUnits.trim());
		}
		catch (NumberFormatException ex) {
			return messageSource.getMessage("orders.error.quantityNotInteger", null, locale);
		}
		if (parsed < 1) {
			return messageSource.getMessage("orders.error.quantityNotPositive", null, locale);
		}
		if (parsed > MAX_QUANTITY_UNITS) {
			return messageSource.getMessage("orders.error.quantityTooLarge", new Object[] { MAX_QUANTITY_UNITS },
					locale);
		}
		return null;
	}

	/**
	 * Returns the localized error for a raw required-date value, or
	 * {@code null} when it is a parseable ISO date not before today.
	 */
	private String validateRequiredDate(String rawRequiredDate, Locale locale) {
		if (rawRequiredDate == null || rawRequiredDate.isBlank()) {
			return messageSource.getMessage("orders.error.dateRequired", null, locale);
		}
		LocalDate parsed;
		try {
			parsed = LocalDate.parse(rawRequiredDate.trim());
		}
		catch (DateTimeParseException ex) {
			return messageSource.getMessage("orders.error.dateRequired", null, locale);
		}
		if (parsed.isBefore(LocalDate.now())) {
			return messageSource.getMessage("orders.error.datePast", null, locale);
		}
		return null;
	}

	private static Long parseProjectId(String rawProjectId) {
		if (rawProjectId == null) {
			return null;
		}
		try {
			return Long.valueOf(rawProjectId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Priority parsePriority(String rawPriority) {
		if (rawPriority == null) {
			return Priority.NORMAL;
		}
		try {
			return Priority.valueOf(rawPriority.trim());
		}
		catch (IllegalArgumentException ex) {
			return Priority.NORMAL;
		}
	}

	/**
	 * Parses a priority with no fallback: {@code null} for a missing or
	 * unknown value.
	 */
	private static Priority parseStrictPriority(String rawPriority) {
		if (rawPriority == null) {
			return null;
		}
		try {
			return Priority.valueOf(rawPriority.trim());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Parses an ISO date, or {@code null} when missing or malformed.
	 */
	private static LocalDate parseDate(String rawDate) {
		if (rawDate == null || rawDate.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(rawDate.trim());
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private void populateFormModel(Model model) {
		model.addAttribute("activeProjects", projectRepository.list(false));
		model.addAttribute("today", LocalDate.now().toString());
	}

	private String renderNewOrderError(Model model, String error, String projectId, String quantityUnits,
			String priority, String requiredDate) {
		populateFormModel(model);
		model.addAttribute("error", error);
		model.addAttribute("projectId", projectId);
		model.addAttribute("quantityUnits", quantityUnits);
		model.addAttribute("priority", priority);
		model.addAttribute("requiredDate", requiredDate);
		return "orders-new";
	}

}

package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

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
 * effect at once.
 */
@Controller
public class OrderController {

	private static final int MAX_QUANTITY_UNITS = 1_000_000;

	private final ProjectRepository projectRepository;

	private final OrderRepository orderRepository;

	private final OrderLineRepository orderLineRepository;

	private final PartRepository partRepository;

	private final ReservationAllocator reservationAllocator;

	private final OrderDetailModel orderDetailModel;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	OrderController(ProjectRepository projectRepository, OrderRepository orderRepository,
			OrderLineRepository orderLineRepository, PartRepository partRepository,
			ReservationAllocator reservationAllocator, OrderDetailModel orderDetailModel, LockRetry lockRetry,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.orderRepository = orderRepository;
		this.orderLineRepository = orderLineRepository;
		this.partRepository = partRepository;
		this.reservationAllocator = reservationAllocator;
		this.orderDetailModel = orderDetailModel;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
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

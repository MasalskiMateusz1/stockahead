package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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

/**
 * Picking screen for both MANAGER and TECHNICIAN: the OPEN-order list, a
 * per-order detail, the completion-report action, and the per-line pick
 * action that shifts quantity from
 * {@code reservedQuantity} to {@code pickedQuantity} and decrements
 * {@code Part.quantity}. A pick never calls {@link ReservationAllocator} —
 * {@code reservedQuantity + pickedQuantity} is invariant across a pick, so
 * the stock pool other orders compete over doesn't change. The order's first
 * successful pick stamps {@code takenAt}, freezing its remaining reservation
 * against future preemption (see {@code ReservationAllocator}). The
 * validate-then-lock-then-write shape mirrors {@code OrderController}/
 * {@code ProjectBomController}: the pre-lock partId lookup uses
 * {@link OrderLineRepository#findPartIdForLine(Long, Long)} — a scalar
 * projection, not {@link #findLine(Long, Long)} — specifically so it does
 * NOT hydrate {@code Order}/{@code OrderLine} entities into the
 * request-scoped persistence context. With {@code spring.jpa.open-in-view}
 * enabled (the default), every {@code TransactionTemplate.execute} call in
 * this request shares the SAME persistence context, so an entity loaded
 * before the lock would still be sitting in Hibernate's first-level cache
 * afterward: the supposedly fresh {@link #findLine(Long, Long)} call made
 * after acquiring the part lock would silently return that same stale cached
 * instance instead of re-querying the database, defeating the whole point of
 * locking before reading. Keeping the pre-lock lookup to a scalar query
 * avoids that trap; only {@code findLine}'s call made after the lock is held
 * ever turns into a managed {@code Order}/{@code OrderLine}.
 */
@Controller
public class PickingController {

	/** The manager's {@code /picking} filter value for "Nieprzypisane". */
	static final String UNASSIGNED_FILTER = "none";

	private final OrderRepository orderRepository;

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final PickingDetailModel pickingDetailModel;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	private final AccountRepository accountRepository;

	private final MessageSource messageSource;

	PickingController(OrderRepository orderRepository, PartRepository partRepository,
			OrderLineRepository orderLineRepository, PickingDetailModel pickingDetailModel, LockRetry lockRetry,
			PlatformTransactionManager transactionManager, AccountRepository accountRepository,
			MessageSource messageSource) {
		this.orderRepository = orderRepository;
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.pickingDetailModel = pickingDetailModel;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.accountRepository = accountRepository;
		this.messageSource = messageSource;
	}

	/**
	 * The OPEN-order list, per viewer. A technician sees only the orders
	 * assigned to them. The manager sees every open order, optionally
	 * narrowed by {@code assignee}: an assignable account's id (see
	 * {@link AccountRepository#findAssignable()}), or {@value #UNASSIGNED_FILTER}
	 * for orders with no assignee or an inactive one. It is only a view
	 * filter, so a malformed value or an id that isn't assignable falls back
	 * to every open order instead of an error page.
	 */
	@GetMapping("/picking")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String list(@RequestParam(name = "assignee", required = false) String assignee,
			Authentication authentication, Model model) {
		boolean isManager = authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch("ROLE_MANAGER"::equals);

		List<Order> orders;
		if (isManager) {
			List<Account> assignableAccounts = accountRepository.findAssignable();
			String selectedAssignee = resolveAssigneeFilter(assignee, assignableAccounts);
			if (selectedAssignee == null) {
				orders = orderRepository.findByStatusWithProject(OrderStatus.OPEN);
			}
			else if (UNASSIGNED_FILTER.equals(selectedAssignee)) {
				orders = orderRepository.findByStatusUnassignedWithProject(OrderStatus.OPEN);
			}
			else {
				orders = orderRepository.findByStatusAndAssigneeWithProject(OrderStatus.OPEN,
						Long.valueOf(selectedAssignee));
			}
			model.addAttribute("assignableAccounts", assignableAccounts);
			model.addAttribute("selectedAssignee", selectedAssignee);
		}
		else {
			Account self = accountRepository.findByCanonicalEmail(Emails.canonical(authentication.getName()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
			orders = orderRepository.findByStatusAndAssigneeWithProject(OrderStatus.OPEN, self.getId());
		}
		model.addAttribute("isManager", isManager);
		model.addAttribute("orders", orders.stream()
			.sorted(ReservationAllocator.ALLOCATION_ORDER)
			.toList());
		Map<Long, Long> toPickByOrderId = new HashMap<>();
		for (Object[] row : orderLineRepository.reservedQuantitiesByOpenOrder()) {
			toPickByOrderId.put((Long) row[0], (Long) row[1]);
		}
		model.addAttribute("toPickByOrderId", toPickByOrderId);
		return "picking-list";
	}

	@GetMapping("/picking/{id}")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String detail(@PathVariable Long id, Model model) {
		return pickingDetailModel.render(model, id);
	}

	@PostMapping("/picking/{orderId}/lines/{lineId}/pick")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String pick(
			@PathVariable Long orderId,
			@PathVariable Long lineId,
			@RequestParam(required = false) String quantity,
			Model model,
			Locale locale) {
		Long partId = transactionTemplate.execute(status -> orderLineRepository.findPartIdForLine(orderId, lineId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));

		String quantityError = validateQuantity(quantity, locale);
		if (quantityError != null) {
			return renderPickError(model, orderId, lineId, quantityError, quantity);
		}
		int parsedQuantity = Integer.parseInt(quantity.trim());

		String businessError;
		try {
			businessError = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				partRepository.findByIdInForUpdate(Set.of(partId));
				OrderLine line = findLine(orderId, lineId);
				Order order = line.getOrder();

				if (order.getStatus() != OrderStatus.OPEN) {
					return messageSource.getMessage("picking.error.orderNotOpen", null, locale);
				}
				if (order.isCompletionReported()) {
					return messageSource.getMessage("picking.error.completionReported", null, locale);
				}
				// reservedQuantity already nets out every prior pick on this line (each
				// pick shifts its amount out of reservedQuantity into pickedQuantity), so
				// it IS the current pickable amount — subtracting pickedQuantity again
				// would double-count past picks and wrongly shrink what's left.
				int maxPickable = line.getReservedQuantity();
				if (parsedQuantity > maxPickable) {
					return messageSource.getMessage("picking.error.quantityExceedsAvailable",
							new Object[] { maxPickable }, locale);
				}

				Part part = line.getPart();
				part.setQuantity(part.getQuantity() - parsedQuantity);
				line.setReservedQuantity(line.getReservedQuantity() - parsedQuantity);
				line.setPickedQuantity(line.getPickedQuantity() + parsedQuantity);
				if (order.getTakenAt() == null) {
					order.setTakenAt(Instant.now());
				}
				orderLineRepository.saveAndFlush(line);
				return null;
			}));
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			return renderPickError(model, orderId, lineId, messageSource.getMessage("picking.error.saveFailed", null,
					locale), quantity);
		}

		if (businessError != null) {
			return renderPickError(model, orderId, lineId, businessError, quantity);
		}

		return "redirect:/picking/" + orderId;
	}

	/**
	 * Reports a taken, still-{@code OPEN} order as finished, stamping
	 * {@code completionReportedAt}/{@code completionReportedBy}. Takes the
	 * same part-row locks a pick on any of the order's lines takes, so a
	 * concurrent pick either commits before the report (and the report sees
	 * it) or runs after it and is rejected by the pick's
	 * {@code picking.error.completionReported} check — never a pick recorded
	 * after the report. The pre-lock lookup is the scalar
	 * {@link OrderLineRepository#findPartIdsForOrder(Long)} for the same
	 * open-in-view stale-cache reason as {@link #pick}; the {@code Order}
	 * entity is only loaded after the locks are held. An empty part-id list
	 * (unknown order, or an order without lines — which can't be taken, and
	 * so can't race a pick) skips the lock; an unknown order is then 404 from
	 * the in-transaction {@code findById}. The reported built count
	 * ({@code builtUnits}) must parse to an integer of at least 0 before any
	 * lock is taken; its upper bound ({@code quantityUnits}) is checked under
	 * the lock, after the state checks.
	 */
	@PostMapping("/picking/{orderId}/report-completion")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String reportCompletion(@PathVariable Long orderId,
			@RequestParam(required = false) String builtUnits, Authentication authentication, Model model,
			Locale locale) {
		Set<Long> partIds = transactionTemplate
			.execute(status -> new HashSet<>(orderLineRepository.findPartIdsForOrder(orderId)));

		Integer parsedBuiltUnits = parseBuiltUnits(builtUnits);
		if (parsedBuiltUnits == null) {
			return renderReportError(model, orderId,
					messageSource.getMessage("picking.error.builtUnitsNotInteger", null, locale), builtUnits);
		}

		String businessError;
		try {
			businessError = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				if (!partIds.isEmpty()) {
					partRepository.findByIdInForUpdate(partIds);
				}
				Order order = orderRepository.findById(orderId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

				if (order.getStatus() != OrderStatus.OPEN) {
					return messageSource.getMessage("picking.error.orderNotOpen", null, locale);
				}
				if (!order.isTaken()) {
					return messageSource.getMessage("picking.error.reportNotTaken", null, locale);
				}
				if (order.isCompletionReported()) {
					return messageSource.getMessage("picking.error.alreadyReported", null, locale);
				}
				if (parsedBuiltUnits > order.getQuantityUnits()) {
					return messageSource.getMessage("picking.error.builtUnitsTooMany",
							new Object[] { order.getQuantityUnits() }, locale);
				}

				Account reporter = accountRepository.findByCanonicalEmail(Emails.canonical(authentication.getName()))
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
				order.setCompletionReportedAt(Instant.now());
				order.setCompletionReportedBy(reporter);
				order.setBuiltUnits(parsedBuiltUnits);
				orderRepository.saveAndFlush(order);
				return null;
			}));
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			return renderReportError(model, orderId,
					messageSource.getMessage("picking.error.reportFailed", null, locale), builtUnits);
		}

		if (businessError != null) {
			return renderReportError(model, orderId, businessError, builtUnits);
		}

		return "redirect:/picking/" + orderId;
	}

	/**
	 * Normalizes the manager's raw {@code assignee} filter: {@value #UNASSIGNED_FILTER},
	 * the id of an assignable account (as a string), or {@code null} for "all"
	 * — which is also the fallback for a blank, malformed or non-assignable
	 * value.
	 */
	private static String resolveAssigneeFilter(String rawAssignee, List<Account> assignableAccounts) {
		if (rawAssignee == null || rawAssignee.isBlank()) {
			return null;
		}
		String trimmed = rawAssignee.trim();
		if (UNASSIGNED_FILTER.equals(trimmed)) {
			return UNASSIGNED_FILTER;
		}
		long id;
		try {
			id = Long.parseLong(trimmed);
		}
		catch (NumberFormatException ex) {
			return null;
		}
		return assignableAccounts.stream().anyMatch(account -> account.getId() == id) ? Long.toString(id) : null;
	}

	/**
	 * Loads the line {@code lineId} of order {@code orderId} inside the
	 * caller's transaction. Unknown order, unknown line, or a line of another
	 * order is 404.
	 */
	private OrderLine findLine(Long orderId, Long lineId) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return order.getLines().stream()
			.filter(line -> line.getId().equals(lineId))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/**
	 * Returns the localized error for a raw pick-quantity value, or
	 * {@code null} when it parses to an integer of at least 1.
	 */
	private String validateQuantity(String rawQuantity, Locale locale) {
		int parsed;
		try {
			parsed = Integer.parseInt(rawQuantity == null ? "" : rawQuantity.trim());
		}
		catch (NumberFormatException ex) {
			return messageSource.getMessage("picking.error.quantityNotInteger", null, locale);
		}
		if (parsed < 1) {
			return messageSource.getMessage("picking.error.quantityNotPositive", null, locale);
		}
		return null;
	}

	/**
	 * Parses a raw built-units value, or returns {@code null} when it is
	 * missing, not an integer (including values beyond the {@code int}
	 * range), or negative.
	 */
	private static Integer parseBuiltUnits(String rawBuiltUnits) {
		if (rawBuiltUnits == null) {
			return null;
		}
		int parsed;
		try {
			parsed = Integer.parseInt(rawBuiltUnits.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
		return parsed < 0 ? null : parsed;
	}

	private String renderPickError(Model model, Long orderId, Long lineId, String error, String rawQuantity) {
		pickingDetailModel.render(model, orderId, error, lineId);
		model.addAttribute("quantity", rawQuantity);
		return PickingDetailModel.VIEW;
	}

	private String renderReportError(Model model, Long orderId, String error, String rawBuiltUnits) {
		pickingDetailModel.render(model, orderId, error, null);
		model.addAttribute("builtUnits", rawBuiltUnits);
		return PickingDetailModel.VIEW;
	}

}

package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

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

import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;

/**
 * Picking screen for both MANAGER and TECHNICIAN: the OPEN-order list, a
 * per-order detail, and the per-line pick action that shifts quantity from
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

	private final OrderRepository orderRepository;

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final PickingDetailModel pickingDetailModel;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	PickingController(OrderRepository orderRepository, PartRepository partRepository,
			OrderLineRepository orderLineRepository, PickingDetailModel pickingDetailModel, LockRetry lockRetry,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.orderRepository = orderRepository;
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.pickingDetailModel = pickingDetailModel;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@GetMapping("/picking")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String list(Model model) {
		model.addAttribute("orders", orderRepository.findByStatusWithProject(OrderStatus.OPEN).stream()
			.sorted(ReservationAllocator.ALLOCATION_ORDER)
			.toList());
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
				int maxPickable = line.getReservedQuantity() - line.getPickedQuantity();
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

	private String renderPickError(Model model, Long orderId, Long lineId, String error, String rawQuantity) {
		pickingDetailModel.render(model, orderId, error, lineId);
		model.addAttribute("quantity", rawQuantity);
		return PickingDetailModel.VIEW;
	}

}

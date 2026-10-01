package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
 */
@Controller
public class OrderController {

	private static final int MAX_QUANTITY_UNITS = 1_000_000;

	private static final int MAX_LOCK_RETRY_ATTEMPTS = 3;

	private final ProjectRepository projectRepository;

	private final OrderRepository orderRepository;

	private final ReservationAllocator reservationAllocator;

	private final OrderDetailModel orderDetailModel;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	OrderController(ProjectRepository projectRepository, OrderRepository orderRepository,
			ReservationAllocator reservationAllocator, OrderDetailModel orderDetailModel,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.orderRepository = orderRepository;
		this.reservationAllocator = reservationAllocator;
		this.orderDetailModel = orderDetailModel;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@GetMapping("/orders")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(Model model) {
		model.addAttribute("orders", orderRepository.findByStatusWithProject(OrderStatus.OPEN).stream()
			.sorted(ReservationAllocator.ALLOCATION_ORDER)
			.toList());
		return "orders-list";
	}

	@GetMapping("/orders/{id}")
	@PreAuthorize("hasRole('MANAGER')")
	public String detail(@PathVariable Long id, Model model) {
		return orderDetailModel.render(model, id);
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
			newOrderId = executeWithLockRetry(() -> transactionTemplate.execute(status -> {
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
	 * Retries {@code action} up to {@link #MAX_LOCK_RETRY_ATTEMPTS} times when
	 * it fails with {@link PessimisticLockingFailureException} (deadlock or
	 * lock-timeout translation), with no backoff between attempts. Re-throws
	 * the last failure once attempts are exhausted.
	 */
	private <T> T executeWithLockRetry(Supplier<T> action) {
		PessimisticLockingFailureException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_LOCK_RETRY_ATTEMPTS; attempt++) {
			try {
				return action.get();
			}
			catch (PessimisticLockingFailureException ex) {
				lastFailure = ex;
			}
		}
		throw lastFailure;
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

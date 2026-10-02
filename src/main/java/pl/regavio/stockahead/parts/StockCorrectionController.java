package pl.regavio.stockahead.parts;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Emails;
import pl.regavio.stockahead.orders.LockRetry;
import pl.regavio.stockahead.orders.OrderLineRepository;
import pl.regavio.stockahead.orders.ReservationAllocator;

/**
 * Manager-only stock correction screen for one part, active or inactive:
 * the current stock, reserved and available units, a "set total" form, an
 * "adjust by delta" form, and the part's past corrections newest first.
 * <p>
 * Both write paths follow {@code DeliveryController}: the raw form strings
 * are validated first without loading a {@link Part} (with
 * {@code spring.jpa.open-in-view} a part hydrated before the part-row lock
 * would be served stale afterwards), then one transaction, wrapped in
 * {@link LockRetry}, locks the part row ({@code findByIdInForUpdate}),
 * computes and checks the new stock against the locked quantity, sets it,
 * inserts the {@link StockCorrection} row, flushes and only then calls
 * {@link ReservationAllocator#reallocateForParts(Set)}. Any rejection rolls
 * back, writes nothing and re-renders the page with the typed inputs kept.
 */
@Controller
public class StockCorrectionController {

	static final String VIEW = "parts-correction";

	static final int MAX_DELTA = 1_000_000;

	static final int MAX_REASON_LENGTH = 500;

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final StockCorrectionRepository stockCorrectionRepository;

	private final AccountRepository accountRepository;

	private final MessageSource messageSource;

	private final ReservationAllocator reservationAllocator;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	StockCorrectionController(PartRepository partRepository, OrderLineRepository orderLineRepository,
			StockCorrectionRepository stockCorrectionRepository, AccountRepository accountRepository,
			MessageSource messageSource, ReservationAllocator reservationAllocator, LockRetry lockRetry,
			PlatformTransactionManager transactionManager) {
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.stockCorrectionRepository = stockCorrectionRepository;
		this.accountRepository = accountRepository;
		this.messageSource = messageSource;
		this.reservationAllocator = reservationAllocator;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/parts/{id}/correction")
	@PreAuthorize("hasRole('MANAGER')")
	public String form(@PathVariable Long id, Model model) {
		return renderForm(model, id, FormInput.BLANK, null);
	}

	/**
	 * Sets the counted total. Rejected when the stock locked in the write
	 * transaction differs from the {@code expectedQuantity} the form was
	 * rendered with (stale), or equals the new total (no change).
	 */
	@PostMapping("/parts/{id}/correction/set")
	@PreAuthorize("hasRole('MANAGER')")
	public String set(@PathVariable Long id, @RequestParam(required = false) String newQuantity,
			@RequestParam(required = false) String expectedQuantity, @RequestParam(required = false) String reason,
			Authentication authentication, Model model, Locale locale, RedirectAttributes redirectAttributes) {
		FormInput input = new FormInput(orEmpty(newQuantity), "", orEmpty(reason), "");

		BigInteger parsed;
		try {
			parsed = new BigInteger(input.newQuantity().trim());
		}
		catch (NumberFormatException ex) {
			return renderForm(model, id, input, message("corrections.error.newQuantityNotInteger", locale));
		}
		if (parsed.signum() < 0) {
			return renderForm(model, id, input, message("corrections.error.newQuantityNegative", locale));
		}
		if (parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
			return renderForm(model, id, input,
					message("corrections.error.newQuantityTooLarge", locale, Integer.MAX_VALUE));
		}
		String reasonError = validateReason(input.setReason(), locale);
		if (reasonError != null) {
			return renderForm(model, id, input, reasonError);
		}

		int target = parsed.intValueExact();
		Integer expected = parseExpected(expectedQuantity);
		return apply(id, input.setReason().strip(), authentication, model, input, locale, redirectAttributes,
				current -> {
					if (expected == null || expected != current) {
						return Target.rejected(message("corrections.error.stale", locale, current));
					}
					if (target == current) {
						return Target.rejected(message("corrections.error.noChange", locale, current));
					}
					return Target.of(target);
				});
	}

	/**
	 * Adds a signed, non-zero delta to the stock locked in the write
	 * transaction; rejected when the result would overflow or drop below 0.
	 */
	@PostMapping("/parts/{id}/correction/adjust")
	@PreAuthorize("hasRole('MANAGER')")
	public String adjust(@PathVariable Long id, @RequestParam(required = false) String delta,
			@RequestParam(required = false) String reason, Authentication authentication, Model model, Locale locale,
			RedirectAttributes redirectAttributes) {
		FormInput input = new FormInput("", orEmpty(delta), "", orEmpty(reason));

		BigInteger parsed;
		try {
			parsed = new BigInteger(input.delta().trim());
		}
		catch (NumberFormatException ex) {
			return renderForm(model, id, input, message("corrections.error.deltaNotInteger", locale));
		}
		if (parsed.signum() == 0) {
			return renderForm(model, id, input, message("corrections.error.deltaZero", locale));
		}
		if (parsed.abs().compareTo(BigInteger.valueOf(MAX_DELTA)) > 0) {
			return renderForm(model, id, input, message("corrections.error.deltaTooLarge", locale, MAX_DELTA));
		}
		String reasonError = validateReason(input.adjustReason(), locale);
		if (reasonError != null) {
			return renderForm(model, id, input, reasonError);
		}

		int change = parsed.intValueExact();
		return apply(id, input.adjustReason().strip(), authentication, model, input, locale, redirectAttributes,
				current -> {
					int after;
					try {
						after = Math.addExact(current, change);
					}
					catch (ArithmeticException ex) {
						return Target.rejected(message("corrections.error.stockOverflow", locale));
					}
					if (after < 0) {
						return Target.rejected(message("corrections.error.belowZero", locale, current));
					}
					return Target.of(after);
				});
	}

	/**
	 * Runs the shared write in one locked, retried transaction and turns its
	 * outcome into a redirect with the {@code stockCorrected} flash, or a
	 * re-render of the page with the inputs kept.
	 */
	private String apply(Long id, String reason, Authentication authentication, Model model, FormInput input,
			Locale locale, RedirectAttributes redirectAttributes, IntFunction<Target> rule) {
		Outcome outcome;
		try {
			outcome = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				Outcome result = applyCorrection(id, reason, authentication, rule);
				if (result.error() != null) {
					status.setRollbackOnly();
				}
				return result;
			}));
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			return renderForm(model, id, input, message("corrections.error.saveFailed", locale));
		}
		if (outcome.error() != null) {
			return renderForm(model, id, input, outcome.error());
		}

		redirectAttributes.addFlashAttribute("stockCorrected", message("corrections.applied", locale,
				outcome.partName(), outcome.before(), outcome.after()));
		return "redirect:/parts";
	}

	/**
	 * Runs inside the write transaction: locks the part row (404 for an
	 * unknown part), lets {@code rule} check the locked stock and compute the
	 * new one, then sets it, inserts the correction row, flushes and
	 * reallocates the part. Returns the outcome; on a rejection nothing has
	 * been mutated.
	 */
	private Outcome applyCorrection(Long id, String reason, Authentication authentication, IntFunction<Target> rule) {
		List<Part> locked = partRepository.findByIdInForUpdate(Set.of(id));
		if (locked.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		Part part = locked.get(0);
		int before = part.getQuantity();
		Target target = rule.apply(before);
		if (target.error() != null) {
			return Outcome.rejected(target.error());
		}

		Account author = accountRepository.findByCanonicalEmail(Emails.canonical(authentication.getName()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		int after = target.quantity();
		part.setQuantity(after);

		StockCorrection correction = new StockCorrection();
		correction.setPart(part);
		correction.setAccount(author);
		correction.setQuantityBefore(before);
		correction.setQuantityAfter(after);
		correction.setReason(reason);
		correction.setCreatedAt(Instant.now());
		stockCorrectionRepository.save(correction);
		// Flush BEFORE reallocating: the allocator reads stock with a scalar query,
		// so an unflushed correction would be invisible to it.
		partRepository.flush();
		reservationAllocator.reallocateForParts(Set.of(id));
		return new Outcome(null, part.getName(), before, after);
	}

	/** Trims (strips) the raw reason and checks it is present and not too long. */
	private String validateReason(String rawReason, Locale locale) {
		String reason = rawReason.strip();
		if (reason.isEmpty()) {
			return message("corrections.error.reasonRequired", locale);
		}
		if (reason.length() > MAX_REASON_LENGTH) {
			return message("corrections.error.reasonTooLong", locale, MAX_REASON_LENGTH);
		}
		return null;
	}

	/**
	 * Parses the hidden {@code expectedQuantity}; anything that is not an
	 * {@code int} gives {@code null}, which the locked check treats as stale.
	 */
	private static Integer parseExpected(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Integer.valueOf(raw.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private String message(String key, Locale locale, Object... args) {
		return messageSource.getMessage(key, args.length == 0 ? null : args, locale);
	}

	/**
	 * Fills the model from the part's current state and re-renders the given
	 * raw inputs; 404 for an unknown part. The hidden {@code expectedQuantity}
	 * always carries the stock read here, so a re-render after a stale
	 * submission offers the fresh value.
	 */
	private String renderForm(Model model, Long id, FormInput input, String error) {
		Part part = partRepository.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		int quantity = part.getQuantity();
		int reserved = Math.toIntExact(orderLineRepository.reservedQuantityForPart(id));
		model.addAttribute("partId", part.getId());
		model.addAttribute("partName", part.getName());
		model.addAttribute("partActive", part.isActive());
		model.addAttribute("quantity", quantity);
		model.addAttribute("reserved", reserved);
		model.addAttribute("available", quantity - reserved);
		model.addAttribute("corrections", stockCorrectionRepository.findByPartIdNewestFirst(id));
		model.addAttribute("expectedQuantity", quantity);
		model.addAttribute("newQuantity", input.newQuantity());
		model.addAttribute("delta", input.delta());
		model.addAttribute("setReason", input.setReason());
		model.addAttribute("adjustReason", input.adjustReason());
		model.addAttribute("error", error);
		return VIEW;
	}

	/** Both forms' raw, untrimmed inputs, re-rendered as typed. */
	record FormInput(String newQuantity, String delta, String setReason, String adjustReason) {

		static final FormInput BLANK = new FormInput("", "", "", "");

	}

	/** The new stock a rule computed from the locked one, or its localized rejection. */
	record Target(int quantity, String error) {

		static Target of(int quantity) {
			return new Target(quantity, null);
		}

		static Target rejected(String error) {
			return new Target(0, error);
		}

	}

	/** A localized rejection, or the applied correction's part name and before/after stock. */
	record Outcome(String error, String partName, int before, int after) {

		static Outcome rejected(String error) {
			return new Outcome(error, null, 0, 0);
		}

	}

}

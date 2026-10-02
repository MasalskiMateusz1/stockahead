package pl.regavio.stockahead.parts;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Delivery receipt screen for both MANAGER and TECHNICIAN: a multi-row form
 * (part, quantity, optional location per row) that raises stock for the
 * delivered parts. Rows are added by a server round-trip ({@code action=addRows})
 * that keeps every typed value, since the app has no JavaScript.
 * <p>
 * Validation works only on the raw form strings and never loads a
 * {@link Part}: with {@code spring.jpa.open-in-view} enabled, a {@code Part}
 * hydrated before the write path's part-row lock would be served stale from
 * the request's persistence context afterwards (see {@code PickingController}).
 * The part dropdown is therefore loaded only on GET and on re-renders
 * (add-rows and validation errors), none of which go on to write.
 */
@Controller
public class DeliveryController {

	static final String VIEW = "deliveries-new";

	static final int DEFAULT_ROWS = 5;

	static final int ROWS_INCREMENT = 5;

	static final int MAX_ROWS = 100;

	static final int MAX_QUANTITY = 1_000_000;

	static final int MAX_LOCATION_LENGTH = 255;

	private static final String ADD_ROWS_ACTION = "addRows";

	private final PartRepository partRepository;

	private final MessageSource messageSource;

	DeliveryController(PartRepository partRepository, MessageSource messageSource) {
		this.partRepository = partRepository;
		this.messageSource = messageSource;
	}

	@GetMapping("/deliveries/new")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String newForm(Model model) {
		List<RowInput> rows = new ArrayList<>();
		for (int i = 0; i < DEFAULT_ROWS; i++) {
			rows.add(RowInput.BLANK);
		}
		return renderForm(model, rows, null);
	}

	@PostMapping("/deliveries")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String receive(@RequestParam Map<String, String> params, Model model, Locale locale) {
		int rowCount = parseRowCount(params.get("rows"));
		List<RowInput> rows = readRows(params, rowCount);

		if (ADD_ROWS_ACTION.equals(params.get("action"))) {
			int extendedCount = Math.min(rowCount + ROWS_INCREMENT, MAX_ROWS);
			while (rows.size() < extendedCount) {
				rows.add(RowInput.BLANK);
			}
			return renderForm(model, rows, null);
		}

		ReceiptValidation validation = validate(rows, locale);
		if (validation.error() != null) {
			return renderForm(model, rows, validation.error());
		}

		// Phase 1: the merged receipt is validated but not written yet. The
		// atomic stock write (lock, increment, flush, reallocate) replaces this
		// redirect in Phase 2.
		return "redirect:/deliveries/new";
	}

	/**
	 * Validates the raw rows in order, stopping at the first error, and merges
	 * the valid rows per part: quantities are summed and the non-blank trimmed
	 * locations collected into an insertion-ordered set.
	 */
	ReceiptValidation validate(List<RowInput> rows, Locale locale) {
		Map<Long, ReceiptLine> merged = new LinkedHashMap<>();
		for (int i = 0; i < rows.size(); i++) {
			RowInput row = rows.get(i);
			int rowNumber = i + 1;
			if (row.isBlank()) {
				continue;
			}
			if (row.partId().isBlank() || row.quantity().isBlank()) {
				return ReceiptValidation.error(rowError("deliveries.error.rowIncomplete", rowNumber, locale));
			}

			Long partId;
			try {
				partId = Long.valueOf(row.partId().trim());
			}
			catch (NumberFormatException ex) {
				return ReceiptValidation.error(rowError("deliveries.error.partInvalid", rowNumber, locale));
			}

			BigInteger quantity;
			try {
				quantity = new BigInteger(row.quantity().trim());
			}
			catch (NumberFormatException ex) {
				return ReceiptValidation.error(rowError("deliveries.error.quantityNotInteger", rowNumber, locale));
			}
			if (quantity.signum() <= 0) {
				return ReceiptValidation.error(rowError("deliveries.error.quantityNotPositive", rowNumber, locale));
			}
			if (quantity.compareTo(BigInteger.valueOf(MAX_QUANTITY)) > 0) {
				return ReceiptValidation.error(messageSource.getMessage("deliveries.error.quantityTooLarge",
						new Object[] { rowNumber, MAX_QUANTITY }, locale));
			}

			String location = row.location().trim();
			if (location.length() > MAX_LOCATION_LENGTH) {
				return ReceiptValidation.error(messageSource.getMessage("deliveries.error.locationTooLong",
						new Object[] { rowNumber, MAX_LOCATION_LENGTH }, locale));
			}

			ReceiptLine line = merged.computeIfAbsent(partId, id -> new ReceiptLine());
			line.add(quantity.intValueExact(), location);
		}
		if (merged.isEmpty()) {
			return ReceiptValidation.error(messageSource.getMessage("deliveries.error.empty", null, locale));
		}
		return new ReceiptValidation(null, merged);
	}

	private String rowError(String key, int rowNumber, Locale locale) {
		return messageSource.getMessage(key, new Object[] { rowNumber }, locale);
	}

	/**
	 * Parses the hidden {@code rows} count; a missing, non-numeric or
	 * non-positive value falls back to {@link #DEFAULT_ROWS}, and the result is
	 * clamped to at most {@link #MAX_ROWS}.
	 */
	private static int parseRowCount(String rawRows) {
		int parsed;
		try {
			parsed = Integer.parseInt(rawRows == null ? "" : rawRows.trim());
		}
		catch (NumberFormatException ex) {
			return DEFAULT_ROWS;
		}
		if (parsed < 1) {
			return DEFAULT_ROWS;
		}
		return Math.min(parsed, MAX_ROWS);
	}

	private static List<RowInput> readRows(Map<String, String> params, int rowCount) {
		List<RowInput> rows = new ArrayList<>(rowCount);
		for (int i = 0; i < rowCount; i++) {
			rows.add(new RowInput(raw(params, "partId" + i), raw(params, "quantity" + i), raw(params, "location" + i)));
		}
		return rows;
	}

	private static String raw(Map<String, String> params, String name) {
		String value = params.get(name);
		return value == null ? "" : value;
	}

	private String renderForm(Model model, List<RowInput> rows, String error) {
		model.addAttribute("parts", partRepository.search("", true));
		model.addAttribute("knownLocations", partRepository.findAllLocationNames());
		model.addAttribute("rowInputs", rows);
		model.addAttribute("rowCount", rows.size());
		model.addAttribute("error", error);
		return VIEW;
	}

	/** One form row's raw, untrimmed input, re-rendered as typed. */
	record RowInput(String partId, String quantity, String location) {

		static final RowInput BLANK = new RowInput("", "", "");

		boolean isBlank() {
			return partId.isBlank() && quantity.isBlank() && location.isBlank();
		}

	}

	/**
	 * A part's merged receipt: the summed quantity of every row naming it and
	 * the distinct non-blank locations given on those rows. At most
	 * {@link #MAX_ROWS} × {@link #MAX_QUANTITY} = 10^8, so the sum fits an int.
	 */
	static final class ReceiptLine {

		private int quantity;

		private final Set<String> locations = new LinkedHashSet<>();

		void add(int rowQuantity, String location) {
			quantity += rowQuantity;
			if (!location.isEmpty()) {
				locations.add(location);
			}
		}

		int quantity() {
			return quantity;
		}

		Set<String> locations() {
			return Collections.unmodifiableSet(locations);
		}

	}

	/**
	 * Either a localized error (with no lines) or the merged receipt keyed by
	 * part id in first-seen order.
	 */
	record ReceiptValidation(String error, Map<Long, ReceiptLine> lines) {

		static ReceiptValidation error(String error) {
			return new ReceiptValidation(error, Map.of());
		}

	}

}

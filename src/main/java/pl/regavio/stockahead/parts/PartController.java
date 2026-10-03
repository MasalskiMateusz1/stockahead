package pl.regavio.stockahead.parts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
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

import pl.regavio.stockahead.orders.OrderLineRepository;

/**
 * Parts catalog search/browse screen, open to any authenticated user, plus
 * manager-only create/edit/deactivate/reactivate actions.
 * {@code showInactive} is honored only for managers; a technician always
 * sees the active catalog regardless of the query parameter.
 */
@Controller
public class PartController {

	private static final int MAX_FIELD_LENGTH = 255;

	private static final String LOCATION_INDEX = "part_locations_part_location_ci_idx";

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	public PartController(PartRepository partRepository, OrderLineRepository orderLineRepository,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@GetMapping("/parts")
	public String list(
			@RequestParam(name = "q", required = false, defaultValue = "") String q,
			@RequestParam(name = "showInactive", required = false, defaultValue = "false") boolean showInactive,
			Authentication authentication,
			Model model) {
		boolean isManager = authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch("ROLE_MANAGER"::equals);

		boolean effectiveShowInactive = isManager && showInactive;

		Map<Long, Integer> reservedByPartId = new HashMap<>();
		for (Object[] row : orderLineRepository.reservedQuantitiesByPart()) {
			Long partId = (Long) row[0];
			Long sumReserved = (Long) row[1];
			reservedByPartId.put(partId, sumReserved.intValue());
		}

		List<PartRow> parts = partRepository.search(q, effectiveShowInactive).stream()
			.map(part -> {
				int reserved = reservedByPartId.getOrDefault(part.getId(), 0);
				return new PartRow(part, reserved, part.getQuantity() - reserved);
			})
			.toList();

		model.addAttribute("parts", parts);
		model.addAttribute("q", q);
		model.addAttribute("showInactive", effectiveShowInactive);
		model.addAttribute("isManager", isManager);
		return "parts-list";
	}

	record PartRow(Part part, int reserved, int available) {
	}

	@GetMapping("/parts/new")
	@PreAuthorize("hasRole('MANAGER')")
	public String newForm() {
		return "parts-new";
	}

	@PostMapping("/parts")
	@PreAuthorize("hasRole('MANAGER')")
	public String create(
			@RequestParam String name,
			@RequestParam String quantity,
			@RequestParam String locations,
			Model model,
			Locale locale) {
		String trimmedName = name == null ? "" : name.trim();
		if (trimmedName.isEmpty()) {
			return renderNewPartError(model, messageSource.getMessage("parts.error.nameRequired", null, locale), name,
					quantity, locations);
		}
		if (trimmedName.length() > MAX_FIELD_LENGTH) {
			return renderNewPartError(model,
					messageSource.getMessage("parts.error.nameTooLong", new Object[] { MAX_FIELD_LENGTH }, locale),
					name, quantity, locations);
		}

		if (partRepository.findByName(trimmedName).isPresent()) {
			return renderNewPartError(model, messageSource.getMessage("parts.error.duplicateName", null, locale),
					name, quantity, locations);
		}

		int parsedQuantity;
		try {
			parsedQuantity = Integer.parseInt(quantity.trim());
		}
		catch (NumberFormatException ex) {
			return renderNewPartError(model,
					messageSource.getMessage("parts.error.quantityNotInteger", null, locale), name, quantity,
					locations);
		}

		if (parsedQuantity < 0) {
			return renderNewPartError(model, messageSource.getMessage("parts.error.quantityNegative", null, locale),
					name, quantity, locations);
		}

		List<String> parsedLocations;
		try {
			parsedLocations = parseLocations(locations, locale);
		}
		catch (IllegalArgumentException ex) {
			return renderNewPartError(model, ex.getMessage(), name, quantity, locations);
		}

		if (parsedLocations.isEmpty()) {
			return renderNewPartError(model, messageSource.getMessage("parts.error.locationsRequired", null, locale),
					name, quantity, locations);
		}

		if (parsedLocations.stream().anyMatch(location -> location.length() > MAX_FIELD_LENGTH)) {
			return renderNewPartError(model,
					messageSource.getMessage("parts.error.locationTooLong", new Object[] { MAX_FIELD_LENGTH }, locale),
					name, quantity, locations);
		}

		try {
			transactionTemplate.executeWithoutResult(status -> {
				LocationSpellings spellings = LocationSpellings.lockShelves(partRepository, parsedLocations);
				// The name lock comes after the shelf locks (the import's order), so a
				// concurrent import can't read the catalog and then insert a case
				// variant of this name before this part commits.
				LocationSpellings.lockPartNames(partRepository, List.of(trimmedName));
				Part part = new Part();
				part.setName(trimmedName);
				part.setQuantity(parsedQuantity);
				part.setActive(true);
				part.setCreatedAt(Instant.now());
				for (String location : parsedLocations) {
					PartLocation partLocation = new PartLocation();
					partLocation.setLocation(spellings.resolve(location));
					part.addLocation(partLocation);
				}
				partRepository.saveAndFlush(part);
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderNewPartError(model, messageSource.getMessage(constraintErrorKey(ex), null, locale),
					name, quantity, locations);
		}

		return "redirect:/parts";
	}

	@GetMapping("/parts/{id}/edit")
	@PreAuthorize("hasRole('MANAGER')")
	public String editForm(@PathVariable Long id, Model model) {
		Part part = partRepository.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("id", part.getId());
		model.addAttribute("name", part.getName());
		model.addAttribute("quantity", part.getQuantity());
		model.addAttribute("locations", joinLocations(part.getLocations()));
		return "parts-edit";
	}

	@PostMapping("/parts/{id}")
	@PreAuthorize("hasRole('MANAGER')")
	public String edit(
			@PathVariable Long id,
			@RequestParam String name,
			@RequestParam String locations,
			Model model,
			Locale locale) {
		Part existing = partRepository.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		int currentQuantity = existing.getQuantity();

		String trimmedName = name == null ? "" : name.trim();
		if (trimmedName.isEmpty()) {
			return renderEditPartError(model, id, messageSource.getMessage("parts.error.nameRequired", null, locale),
					name, currentQuantity, locations);
		}
		if (trimmedName.length() > MAX_FIELD_LENGTH) {
			return renderEditPartError(model, id,
					messageSource.getMessage("parts.error.nameTooLong", new Object[] { MAX_FIELD_LENGTH }, locale),
					name, currentQuantity, locations);
		}

		Optional<Part> conflict = partRepository.findByName(trimmedName)
			.filter(other -> !other.getId().equals(id));
		if (conflict.isPresent()) {
			return renderEditPartError(model, id, messageSource.getMessage("parts.error.duplicateName", null, locale),
					name, currentQuantity, locations);
		}

		List<String> parsedLocations;
		try {
			parsedLocations = parseLocations(locations, locale);
		}
		catch (IllegalArgumentException ex) {
			return renderEditPartError(model, id, ex.getMessage(), name, currentQuantity, locations);
		}

		if (parsedLocations.isEmpty()) {
			return renderEditPartError(model, id,
					messageSource.getMessage("parts.error.locationsRequired", null, locale), name, currentQuantity,
					locations);
		}

		if (parsedLocations.stream().anyMatch(location -> location.length() > MAX_FIELD_LENGTH)) {
			return renderEditPartError(model, id,
					messageSource.getMessage("parts.error.locationTooLong", new Object[] { MAX_FIELD_LENGTH }, locale),
					name, currentQuantity, locations);
		}

		try {
			transactionTemplate.executeWithoutResult(status -> {
				// Lock every target shelf (new and re-spelled ones included) before
				// anything is read or written, so a concurrent writer of the same
				// shelf commits entirely before or after this edit.
				LocationSpellings spellings = LocationSpellings.lockShelves(partRepository, parsedLocations);
				Part part = partRepository.findById(id)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				part.setName(trimmedName);
				List<String> respelled = reconcileLocations(part, parsedLocations, spellings);
				// Flush this part's own rows first: the bulk update below bypasses
				// the persistence context and must never touch them.
				partRepository.saveAndFlush(part);
				for (String spelling : respelled) {
					partRepository.respellOnOtherParts(spelling, part.getId());
				}
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderEditPartError(model, id, messageSource.getMessage(constraintErrorKey(ex), null, locale),
					name, currentQuantity, locations);
		}

		return "redirect:/parts";
	}

	@PostMapping("/parts/{id}/deactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String deactivate(@PathVariable Long id) {
		transactionTemplate.executeWithoutResult(status -> {
			Part part = partRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			part.setActive(false);
			partRepository.saveAndFlush(part);
		});
		return "redirect:/parts";
	}

	@PostMapping("/parts/{id}/reactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String reactivate(@PathVariable Long id) {
		transactionTemplate.executeWithoutResult(status -> {
			Part part = partRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			part.setActive(true);
			partRepository.saveAndFlush(part);
		});
		return "redirect:/parts?showInactive=true";
	}

	/**
	 * Splits a locations textarea's raw text on newlines, normalizes each line
	 * ({@link PartLocation#normalize}), drops blank lines, and rejects the
	 * submission if any two lines name the same location
	 * ({@link PartLocation#sameLocation}).
	 */
	private List<String> parseLocations(String rawLocations, Locale locale) {
		String text = rawLocations == null ? "" : rawLocations;
		List<String> parsed = new ArrayList<>();
		for (String rawLine : text.split("\r?\n")) {
			String trimmed = PartLocation.normalize(rawLine);
			if (trimmed.isEmpty()) {
				continue;
			}
			if (parsed.stream().anyMatch(known -> PartLocation.sameLocation(known, trimmed))) {
				throw new IllegalArgumentException(
						messageSource.getMessage("parts.error.locationDuplicate", new Object[] { trimmed }, locale));
			}
			parsed.add(trimmed);
		}
		return parsed;
	}

	/**
	 * Reconciles a managed part's location collection in place against a
	 * normalized target set: retains matching rows, removes rows with no
	 * match in the target set (orphanRemoval deletes them), and adds new
	 * rows for target strings with no matching existing row. Matching is
	 * case-insensitive ({@link PartLocation#sameLocation}): a row matched
	 * only by case is kept and re-spelled to the target, never deleted and
	 * re-inserted. The {@code (part_id, lower(location))} index means a part
	 * holds at most one row per shelf, so each target matches at most one
	 * existing row.
	 * <p>
	 * New rows take the shelf's existing spelling from any other part
	 * ({@link LocationSpellings}), resolved before the collection is touched.
	 * Returns the typed spellings of rows that changed only by case; the
	 * caller re-spells those shelves on every other part after flushing.
	 */
	private List<String> reconcileLocations(Part part, List<String> targetLocations, LocationSpellings spellings) {
		List<PartLocation> existingLocations = new ArrayList<>(part.getLocations());
		Map<String, PartLocation> matched = new HashMap<>();
		List<String> respelled = new ArrayList<>();
		for (String targetLocation : targetLocations) {
			existingLocations.stream()
				.filter(existing -> PartLocation.sameLocation(existing.getLocation(), targetLocation))
				.findFirst()
				.ifPresent(existing -> {
					if (!existing.getLocation().equals(targetLocation)) {
						existing.setLocation(targetLocation);
						respelled.add(targetLocation);
					}
					matched.put(targetLocation, existing);
				});
		}
		Map<String, String> addedSpellings = new HashMap<>();
		for (String targetLocation : targetLocations) {
			if (!matched.containsKey(targetLocation)) {
				addedSpellings.put(targetLocation, spellings.resolve(targetLocation));
			}
		}
		for (PartLocation existingLocation : existingLocations) {
			if (!matched.containsValue(existingLocation)) {
				part.removeLocation(existingLocation);
			}
		}
		for (String targetLocation : targetLocations) {
			if (!matched.containsKey(targetLocation)) {
				PartLocation newLocation = new PartLocation();
				newLocation.setLocation(addedSpellings.get(targetLocation));
				part.addLocation(newLocation);
			}
		}
		return respelled;
	}

	/**
	 * A part write can break two unique constraints: the part name, or the
	 * per-part location index when a concurrent write added the same shelf
	 * first. Picks the message for whichever one the database reported.
	 */
	private String constraintErrorKey(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& LOCATION_INDEX.equals(violation.getConstraintName())) {
				return "parts.error.locationConflict";
			}
		}
		return "parts.error.duplicateName";
	}

	private String joinLocations(List<PartLocation> locations) {
		return locations.stream()
			.map(PartLocation::getLocation)
			.collect(Collectors.joining("\n"));
	}

	private String renderNewPartError(Model model, String error, String name, String quantity, String locations) {
		model.addAttribute("error", error);
		model.addAttribute("name", name);
		model.addAttribute("quantity", quantity);
		model.addAttribute("locations", locations);
		return "parts-new";
	}

	private String renderEditPartError(Model model, Long id, String error, String name, int quantity,
			String locations) {
		model.addAttribute("id", id);
		model.addAttribute("error", error);
		model.addAttribute("name", name);
		model.addAttribute("quantity", quantity);
		model.addAttribute("locations", locations);
		return "parts-edit";
	}

}

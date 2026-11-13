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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.account.CompanyRepository;
import pl.regavio.stockahead.orders.OrderLineRepository;
import pl.regavio.stockahead.security.CompanyUser;

/**
 * Parts catalog search/browse screen, open to any authenticated user, plus
 * manager-only create/edit/deactivate/reactivate actions. Every route sees
 * only the principal's company: another company's part id answers 404.
 * {@code showInactive} is honored only for managers; a technician always
 * sees the active catalog regardless of the query parameter.
 */
@Controller
public class PartController {

	private static final int MAX_FIELD_LENGTH = 255;

	private static final String LOCATION_INDEX = "part_locations_part_location_ci_idx";

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final CompanyRepository companyRepository;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;
	private final StockService stockService;

	public PartController(PartRepository partRepository, OrderLineRepository orderLineRepository,
			CompanyRepository companyRepository, PlatformTransactionManager transactionManager,
			MessageSource messageSource, StockService stockService) {
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.companyRepository = companyRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
		this.stockService = stockService;
	}

	@GetMapping("/parts")
	public String list(
			@RequestParam(name = "q", required = false, defaultValue = "") String q,
			@RequestParam(name = "showInactive", required = false, defaultValue = "false") boolean showInactive,
			Authentication authentication,
			@AuthenticationPrincipal CompanyUser principal,
			Model model) {
		boolean isManager = authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch("ROLE_MANAGER"::equals);

		boolean effectiveShowInactive = isManager && showInactive;

		Map<Long, Integer> reservedByPartId = new HashMap<>();
		for (Object[] row : orderLineRepository.reservedQuantitiesByPart(principal.companyId())) {
			Long partId = (Long) row[0];
			Long sumReserved = (Long) row[1];
			reservedByPartId.put(partId, sumReserved.intValue());
		}

		List<PartRow> parts = partRepository.search(principal.companyId(), q, effectiveShowInactive).stream()
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
			@RequestParam(required = false) String initialLocation,
			@AuthenticationPrincipal CompanyUser principal,
			Model model,
			Locale locale) {
		model.addAttribute("initialLocation", initialLocation);
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

		if (partRepository.findByCompanyIdAndName(principal.companyId(), trimmedName).isPresent()) {
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
		String destination = initialLocation == null ? "" : PartLocation.normalize(initialLocation);
		if (parsedQuantity > 0 && parsedLocations.stream().noneMatch(location -> PartLocation.sameLocation(location, destination))) {
			return renderNewPartError(model,
					messageSource.getMessage("parts.error.initialLocationRequired", null, locale), name, quantity,
					locations);
		}
		if (parsedQuantity == 0 && !destination.isEmpty()) {
			return renderNewPartError(model,
					messageSource.getMessage("parts.error.initialLocationUnexpected", null, locale), name, quantity,
					locations);
		}

		boolean created;
		try {
			created = transactionTemplate.execute(status -> {
				LocationSpellings spellings = LocationSpellings.lockShelves(partRepository, principal.companyId(),
						parsedLocations);
				// The name lock comes after the shelf locks (the import's order), so a
				// concurrent import can't read the catalog and then insert a case
				// variant of this name before this part commits.
				LocationSpellings.lockPartNames(partRepository, principal.companyId(), List.of(trimmedName));
				// Under the name lock, a case variant committed meanwhile (e.g. by an
				// import) is visible; the case-sensitive UNIQUE constraint alone
				// would let it through as a second part.
				if (!CatalogNameIndex.of(partRepository.findAllIdsAndNames(principal.companyId()))
					.matches(trimmedName)
					.isEmpty()) {
					return false;
				}
				Part part = new Part();
				part.setName(trimmedName);
				part.setQuantity(parsedQuantity);
				part.setActive(true);
				part.setCreatedAt(Instant.now());
				part.setCompany(companyRepository.getReferenceById(principal.companyId()));
				for (String location : parsedLocations) {
					PartLocation partLocation = new PartLocation();
					partLocation.setLocation(spellings.resolve(location));
					if (parsedQuantity > 0 && PartLocation.sameLocation(location, destination)) {
						partLocation.setQuantity(parsedQuantity);
					}
					part.addLocation(partLocation);
				}
				partRepository.saveAndFlush(part);
				return true;
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderNewPartError(model, messageSource.getMessage(constraintErrorKey(ex), null, locale),
					name, quantity, locations);
		}
		if (!created) {
			return renderNewPartError(model, messageSource.getMessage("parts.error.duplicateName", null, locale),
					name, quantity, locations);
		}

		return "redirect:/parts";
	}

	@GetMapping("/parts/{id}/edit")
	@PreAuthorize("hasRole('MANAGER')")
	public String editForm(@PathVariable Long id, @AuthenticationPrincipal CompanyUser principal, Model model) {
		Part part = partRepository.findByIdAndCompanyId(id, principal.companyId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("id", part.getId());
		model.addAttribute("name", part.getName());
		model.addAttribute("quantity", part.getQuantity());
		model.addAttribute("locations", joinLocations(part.getLocations()));
		model.addAttribute("locationRows", List.copyOf(part.getLocations()));
		return "parts-edit";
	}

	@PostMapping("/parts/{id}")
	@PreAuthorize("hasRole('MANAGER')")
	public String edit(
			@PathVariable Long id,
			@RequestParam String name,
			@RequestParam(required = false) String locations,
			@RequestParam(required = false) List<Long> locationId,
			@RequestParam(required = false) List<String> locationName,
			@RequestParam(required = false) String newLocations,
			@AuthenticationPrincipal CompanyUser principal,
			Model model,
			Locale locale) {
		Part existing = partRepository.findByIdAndCompanyId(id, principal.companyId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("locationRows", List.copyOf(existing.getLocations()));
		int currentQuantity = existing.getQuantity();
		// The edit form identifies existing rows by id (so a rename keeps the
		// row and its stock); a part without rows submits no ids at all. Only a
		// request carrying the legacy textarea takes the text-matching path.
		boolean byId = locationId != null || locations == null;
		List<Long> rowIds = locationId == null ? List.of() : locationId;
		List<String> rowNames = locationName == null ? List.of() : locationName;
		if (byId) {
			if (rowIds.size() != rowNames.size()) {
				return renderEditPartError(model, id,
						messageSource.getMessage("parts.error.locationRowsInvalid", null, locale), name, currentQuantity,
						locations);
			}
			locations = String.join("\n", rowNames) + "\n" + (newLocations == null ? "" : newLocations);
		}

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

		Optional<Part> conflict = partRepository.findByCompanyIdAndName(principal.companyId(), trimmedName)
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

		boolean renamed;
		try {
			renamed = transactionTemplate.execute(status -> {
				// Lock every target shelf (new and re-spelled ones included) before
				// anything is read or written, so a concurrent writer of the same
				// shelf commits entirely before or after this edit.
				LocationSpellings spellings = LocationSpellings.lockShelves(partRepository, principal.companyId(),
						parsedLocations);
				// The name lock comes after the shelf locks (the import's and
				// create's order), so a concurrent import or create can't read the
				// catalog and then insert a case variant of this name meanwhile.
				LocationSpellings.lockPartNames(partRepository, principal.companyId(), List.of(trimmedName));
				// Under the name lock, another part holding a case variant is
				// visible; the case-sensitive UNIQUE constraint alone would let it
				// through. The part's own name may change case.
				if (CatalogNameIndex.of(partRepository.findAllIdsAndNames(principal.companyId()))
					.matches(trimmedName)
					.stream()
					.anyMatch(other -> !other.id().equals(id))) {
					return false;
				}
				Part part = stockService.lock(principal.companyId(), id);
				part.setName(trimmedName);
				List<String> respelled = byId
					? reconcileLocationsById(part, rowIds, rowNames, newLocations, spellings, locale)
					: reconcileLocations(part, parsedLocations, spellings, locale);
				// Flush this part's own rows first: the bulk update below bypasses
				// the persistence context and must never touch them.
				partRepository.saveAndFlush(part);
				for (String spelling : respelled) {
					partRepository.respellOnOtherParts(principal.companyId(), spelling, part.getId());
				}
				return true;
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderEditPartError(model, id, messageSource.getMessage(constraintErrorKey(ex), null, locale),
					name, currentQuantity, locations);
		}
		catch (IllegalArgumentException ex) {
			return renderEditPartError(model, id, ex.getMessage(), name, currentQuantity, locations);
		}
		if (!renamed) {
			return renderEditPartError(model, id, messageSource.getMessage("parts.error.duplicateName", null, locale),
					name, currentQuantity, locations);
		}

		return "redirect:/parts";
	}

	@PostMapping("/parts/{id}/deactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String deactivate(@PathVariable Long id, @AuthenticationPrincipal CompanyUser principal) {
		transactionTemplate.executeWithoutResult(status -> {
			Part part = partRepository.findByIdAndCompanyId(id, principal.companyId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			part.setActive(false);
			partRepository.saveAndFlush(part);
		});
		return "redirect:/parts";
	}

	@PostMapping("/parts/{id}/reactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String reactivate(@PathVariable Long id, @AuthenticationPrincipal CompanyUser principal) {
		transactionTemplate.executeWithoutResult(status -> {
			Part part = partRepository.findByIdAndCompanyId(id, principal.companyId())
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
	private List<String> reconcileLocations(Part part, List<String> targetLocations, LocationSpellings spellings,
			Locale locale) {
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
				rejectRemovalOfStock(existingLocation, locale);
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
	 * Reconciles a managed part's rows by id: each submitted row keeps its
	 * identity and stock under its new name, a blank name or an omitted row
	 * removes it (only when it holds no stock), and {@code newLocations} adds
	 * empty rows. A rename to another shelf takes that shelf's company
	 * spelling ({@link LocationSpellings}); a case-only change re-spells the
	 * shelf and is returned for the caller to re-spell on other parts.
	 */
	private List<String> reconcileLocationsById(Part part, List<Long> ids, List<String> names,
			String newLocations, LocationSpellings spellings, Locale locale) {
		List<PartLocation> previous = new ArrayList<>(part.getLocations());
		List<String> respelled = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			Long rowId = ids.get(i);
			PartLocation row = previous.stream().filter(candidate -> candidate.getId().equals(rowId))
				.findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			String target = PartLocation.normalize(names.get(i));
			if (target.isEmpty()) {
				rejectRemovalOfStock(row, locale);
				part.removeLocation(row);
			}
			else if (PartLocation.sameLocation(row.getLocation(), target)) {
				if (!row.getLocation().equals(target)) {
					row.setLocation(target);
					respelled.add(target);
				}
			}
			else {
				row.setLocation(spellings.resolve(target));
			}
		}
		for (PartLocation row : previous) {
			if (!ids.contains(row.getId())) {
				rejectRemovalOfStock(row, locale);
				part.removeLocation(row);
			}
		}
		for (String name : parseLocations(newLocations, locale)) {
			PartLocation row = new PartLocation();
			row.setLocation(spellings.resolve(name));
			part.addLocation(row);
		}
		return respelled;
	}

	/** Labels are edited, stock is never discarded: a stocked row must be emptied first. */
	private void rejectRemovalOfStock(PartLocation row, Locale locale) {
		if (row.getQuantity() > 0) {
			throw new IllegalArgumentException(messageSource.getMessage("parts.error.locationHasStock",
					new Object[] { row.getLocation(), row.getQuantity() }, locale));
		}
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

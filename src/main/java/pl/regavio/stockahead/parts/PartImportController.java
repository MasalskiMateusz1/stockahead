package pl.regavio.stockahead.parts;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpSession;
import pl.regavio.stockahead.orders.LockRetry;
import pl.regavio.stockahead.orders.ReservationAllocator;
import pl.regavio.stockahead.parts.CatalogNameIndex.CatalogPart;
import pl.regavio.stockahead.parts.PartsImportFile.ImportLine;
import pl.regavio.stockahead.parts.PendingImport.Snapshot;

/**
 * Manager-only parts CSV import: the upload page documenting the file format,
 * and the upload itself, which reads the file ({@link PartsCsvReader}),
 * validates and merges its rows ({@link PartsImportFile}), resolves them
 * against the catalog ({@link ImportPreview}) and either re-renders the page
 * with every error found, at most {@value #MAX_ERRORS_SHOWN} of them listed,
 * or renders the preview of the resulting stock. The preview never writes;
 * it is kept in the session as a {@link PendingImport} (one per session: each
 * upload replaces it, cancel removes it).
 * <p>
 * Accepting the preview ({@code POST /parts/import/confirm}) writes it all or
 * nothing in one transaction, in the lock order every stock writer shares:
 * the file's shelves, then its part names, then the matched part rows. Under
 * those locks the lines are resolved against the catalog again and compared
 * with the preview's snapshot; any difference in a matched part or its stock
 * writes nothing and shows a refreshed preview to accept again. The confirm
 * request loads no {@link Part} before its locks (with
 * {@code spring.jpa.open-in-view} on, an entity loaded earlier would be
 * served stale), and its transaction returns only an {@link Outcome}: after
 * a rollback every entity it loaded is detached, so each refreshed preview is
 * built afresh after the transaction.
 * <p>
 * The {@value #MAX_FILE_BYTES}-byte limit is checked here, not by the
 * multipart limits (set higher, at 10 MB): a multipart size failure happens
 * before the controller runs, so the CSRF filter would answer 403 or the
 * container would reset the connection instead of showing a message.
 */
@Controller
public class PartImportController {

	static final String VIEW = "parts-import";

	static final String PREVIEW_VIEW = "parts-import-preview";

	static final long MAX_FILE_BYTES = 1024 * 1024;

	static final int MAX_ERRORS_SHOWN = 50;

	private final PartRepository partRepository;

	private final MessageSource messageSource;

	private final ReservationAllocator reservationAllocator;

	private final LockRetry lockRetry;

	private final TransactionTemplate transactionTemplate;

	PartImportController(PartRepository partRepository, MessageSource messageSource,
			ReservationAllocator reservationAllocator, LockRetry lockRetry,
			PlatformTransactionManager transactionManager) {
		this.partRepository = partRepository;
		this.messageSource = messageSource;
		this.reservationAllocator = reservationAllocator;
		this.lockRetry = lockRetry;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/parts/import")
	@PreAuthorize("hasRole('MANAGER')")
	public String uploadForm() {
		return VIEW;
	}

	@PostMapping("/parts/import")
	@PreAuthorize("hasRole('MANAGER')")
	public String upload(@RequestParam(name = "file", required = false) MultipartFile file, Model model,
			Locale locale, HttpSession session) {
		// A new upload replaces any pending import, valid or not.
		session.removeAttribute(PendingImport.SESSION_ATTRIBUTE);
		if (file == null || file.isEmpty()) {
			return renderErrors(model, List.of(ImportError.ofFile("partsImport.error.fileRequired")), locale);
		}
		if (file.getSize() > MAX_FILE_BYTES) {
			return renderErrors(model, List.of(ImportError.ofFile("partsImport.error.fileTooLarge")), locale);
		}

		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			return renderErrors(model, List.of(ImportError.ofFile("partsImport.error.readFailed")), locale);
		}

		PartsCsvReader.Result result = PartsCsvReader.read(bytes);
		if (result.isError()) {
			return renderErrors(model, List.of(result.error()), locale);
		}
		PartsImportFile importFile = PartsImportFile.parse(result.records());
		List<PartsImportFile.ImportLine> lines = List.copyOf(importFile.lines().values());
		ImportPreview.Result preview = lines.isEmpty() ? null : ImportPreview.build(lines, partRepository);
		if (importFile.hasErrors() || preview == null || preview.hasErrors()) {
			List<ImportError> errors = new ArrayList<>(importFile.errors());
			if (preview != null) {
				errors.addAll(preview.errors());
			}
			// File-level errors first, then by row; stable, so one row's errors keep their order.
			errors.sort(Comparator.comparingInt(ImportError::rowNumber));
			return renderErrors(model, errors, locale);
		}

		PendingImport pending = PendingImport.create(lines, preview.snapshots());
		session.setAttribute(PendingImport.SESSION_ATTRIBUTE, pending);
		return renderPreview(model, pending, preview, null);
	}

	@PostMapping("/parts/import/cancel")
	@PreAuthorize("hasRole('MANAGER')")
	public String cancel(HttpSession session) {
		session.removeAttribute(PendingImport.SESSION_ATTRIBUTE);
		return "redirect:/parts";
	}

	@PostMapping("/parts/import/confirm")
	@PreAuthorize("hasRole('MANAGER')")
	public String confirm(@RequestParam(name = "token", required = false) String token, Model model, Locale locale,
			HttpSession session, RedirectAttributes redirectAttributes) {
		PendingImport pending = session.getAttribute(PendingImport.SESSION_ATTRIBUTE) instanceof PendingImport p ? p
				: null;
		if (pending == null || token == null || !pending.token().equals(token)) {
			// Another tab's preview, an expired session or a repeated submit: write nothing.
			model.addAttribute("notice", messageSource.getMessage("partsImport.error.previewExpired", null, locale));
			return VIEW;
		}

		Outcome outcome;
		try {
			outcome = lockRetry.executeWithLockRetry(() -> transactionTemplate.execute(status -> {
				Outcome result = applyImport(pending);
				if (result.status() != OutcomeStatus.APPLIED) {
					status.setRollbackOnly();
				}
				return result;
			}));
		}
		catch (DataIntegrityViolationException ex) {
			return rePreview(model, locale, session, pending,
					messageSource.getMessage("partsImport.notice.catalogChanged", null, locale));
		}
		catch (PessimisticLockingFailureException ex) {
			return rePreview(model, locale, session, pending,
					messageSource.getMessage("partsImport.notice.saveFailed", null, locale));
		}

		switch (outcome.status()) {
			case STALE:
				return rePreview(model, locale, session, pending,
						messageSource.getMessage("partsImport.notice.stale", null, locale));
			case REJECTED:
				ImportError error = outcome.errors().get(0);
				return rePreview(model, locale, session, pending,
						messageSource.getMessage(error.messageKey(), resolveArgs(error, locale), locale));
			default:
				break;
		}

		session.removeAttribute(PendingImport.SESSION_ATTRIBUTE);
		int newParts = (int) pending.snapshots().stream().filter(snapshot -> snapshot.partId() == null).count();
		int updatedParts = pending.snapshots().size() - newParts;
		long unitsAdded = pending.lines().stream().mapToLong(ImportLine::quantity).sum();
		redirectAttributes.addFlashAttribute("partsImported", messageSource.getMessage("partsImport.imported",
				new Object[] { newParts, updatedParts, unitsAdded }, locale));
		return "redirect:/parts";
	}

	/**
	 * Runs inside the confirm transaction. Locks the file's shelves, then its
	 * part names ({@link LocationSpellings#lockPartNames}, before the catalog
	 * read, so a case variant committed meanwhile is always seen), reads the
	 * catalog's {@code (id, name)} pairs, resolves every line and locks the
	 * matched part rows. A locked part whose name no longer matches its line
	 * (renamed meanwhile), or a line whose match (part id, or none) or stock
	 * differs from the snapshot, makes the import {@code STALE}; a name now
	 * matching several parts makes it {@code REJECTED}. Only then does it
	 * mutate: stock raised ({@link Math#addExact}), missing locations added in
	 * the shelf's spelling, new parts created, the persistence context
	 * flushed and the existing parts whose stock rose reallocated.
	 */
	private Outcome applyImport(PendingImport pending) {
		List<ImportLine> lines = pending.lines();
		List<Snapshot> snapshots = pending.snapshots();
		// Shelves, then part names, then part rows: the lock order every writer shares.
		LocationSpellings spellings = LocationSpellings.lockShelves(partRepository,
				lines.stream().flatMap(line -> line.locations().stream()).toList());
		LocationSpellings.lockPartNames(partRepository, lines.stream().map(ImportLine::name).toList());

		CatalogNameIndex index = CatalogNameIndex.of(partRepository.findAllIdsAndNames());
		List<ImportError> errors = new ArrayList<>();
		boolean stale = false;
		Long[] matchedIdPerLine = new Long[lines.size()];
		Set<Long> matchedIds = new LinkedHashSet<>();
		for (int i = 0; i < lines.size(); i++) {
			ImportLine line = lines.get(i);
			List<CatalogPart> matches = index.matches(line.name());
			if (matches.size() > 1) {
				errors.add(ImportError.ofRow(line.rowNumbers().get(0), "partsImport.error.ambiguousName", line.name(),
						matches.stream().map(CatalogPart::name).sorted().collect(Collectors.joining(", "))));
				continue;
			}
			Long matchedId = matches.isEmpty() ? null : matches.get(0).id();
			matchedIdPerLine[i] = matchedId;
			if (matchedId != null) {
				matchedIds.add(matchedId);
			}
			if (!Objects.equals(matchedId, snapshots.get(i).partId())) {
				stale = true;
			}
		}
		Map<Long, Part> lockedById = matchedIds.isEmpty() ? Map.of()
				: partRepository.findByIdInForUpdate(matchedIds)
					.stream()
					.collect(Collectors.toMap(Part::getId, Function.identity()));
		if (!matchedIds.isEmpty()) {
			// Initializes the locked parts' locations in one query, so adding
			// missing locations below doesn't run one lazy load per part while
			// every lock is held. The managed instances stay the locked ones.
			partRepository.findWithLocationsByIdIn(matchedIds);
		}
		for (int i = 0; i < lines.size(); i++) {
			Long matchedId = matchedIdPerLine[i];
			if (matchedId == null) {
				continue;
			}
			Part part = lockedById.get(matchedId);
			if (part == null
					|| !PartsImportFile.nameKey(part.getName()).equals(PartsImportFile.nameKey(lines.get(i).name()))
					|| part.getQuantity() != snapshots.get(i).quantityBefore()) {
				stale = true;
			}
		}
		if (stale) {
			return Outcome.STALE;
		}
		if (!errors.isEmpty()) {
			return new Outcome(OutcomeStatus.REJECTED, errors);
		}

		Map<Long, Integer> newQuantities = new HashMap<>();
		for (int i = 0; i < lines.size(); i++) {
			Long matchedId = matchedIdPerLine[i];
			if (matchedId == null) {
				continue;
			}
			Part part = lockedById.get(matchedId);
			try {
				newQuantities.put(matchedId,
						Math.addExact(part.getQuantity(), Math.toIntExact(lines.get(i).quantity())));
			}
			catch (ArithmeticException ex) {
				return new Outcome(OutcomeStatus.REJECTED, List.of(ImportError.ofRow(lines.get(i).rowNumbers().get(0),
						"partsImport.error.stockOverflow", part.getName(), Integer.MAX_VALUE)));
			}
		}

		// Resolve every location to its shelf's spelling in file order before
		// mutating anything: the first spelling in the file wins for a shelf new
		// to the database.
		Map<String, String> resolvedSpellings = new HashMap<>();
		for (ImportLine line : lines) {
			for (String location : line.locations()) {
				resolvedSpellings.computeIfAbsent(location, spellings::resolve);
			}
		}

		Set<Long> raisedIds = new LinkedHashSet<>();
		Instant now = Instant.now();
		for (int i = 0; i < lines.size(); i++) {
			ImportLine line = lines.get(i);
			Long matchedId = matchedIdPerLine[i];
			if (matchedId == null) {
				continue;
			}
			Part part = lockedById.get(matchedId);
			part.setQuantity(newQuantities.get(matchedId));
			addMissingLocations(part, line, resolvedSpellings);
			if (line.quantity() > 0) {
				raisedIds.add(matchedId);
			}
		}
		// New parts after the existing ones are mutated, before the flush.
		for (int i = 0; i < lines.size(); i++) {
			if (matchedIdPerLine[i] != null) {
				continue;
			}
			ImportLine line = lines.get(i);
			Part part = new Part();
			part.setName(line.name());
			part.setQuantity(Math.toIntExact(line.quantity()));
			part.setActive(true);
			part.setCreatedAt(now);
			addMissingLocations(part, line, resolvedSpellings);
			partRepository.save(part);
		}
		// Flush BEFORE reallocating: the allocator reads stock with a scalar query.
		// New parts are left out, since no order can reference them yet.
		partRepository.flush();
		reservationAllocator.reallocateForParts(raisedIds);
		return Outcome.APPLIED;
	}

	/** Adds each line location the part lacks ({@link PartLocation#sameLocation}) in its resolved spelling. */
	private static void addMissingLocations(Part part, ImportLine line, Map<String, String> resolvedSpellings) {
		for (String location : line.locations()) {
			boolean present = part.getLocations()
				.stream()
				.anyMatch(existing -> PartLocation.sameLocation(existing.getLocation(), location));
			if (!present) {
				PartLocation partLocation = new PartLocation();
				partLocation.setLocation(resolvedSpellings.get(location));
				part.addLocation(partLocation);
			}
		}
	}

	/**
	 * After a confirm that wrote nothing: builds the preview of the pending
	 * lines afresh (an unlocked read after the transaction), keeps it under
	 * the same token with its new snapshot, and renders it with
	 * {@code notice}. If the catalog now makes the file invalid, the pending
	 * import is dropped and the errors are listed on the upload page.
	 */
	private String rePreview(Model model, Locale locale, HttpSession session, PendingImport pending, String notice) {
		ImportPreview.Result preview = ImportPreview.build(pending.lines(), partRepository);
		if (preview.hasErrors()) {
			session.removeAttribute(PendingImport.SESSION_ATTRIBUTE);
			return renderErrors(model, preview.errors(), locale);
		}
		PendingImport refreshed = new PendingImport(pending.token(), pending.lines(), preview.snapshots());
		session.setAttribute(PendingImport.SESSION_ATTRIBUTE, refreshed);
		return renderPreview(model, refreshed, preview, notice);
	}

	/**
	 * What the confirm transaction decided, as plain values only: applied,
	 * stale (re-preview), or rejected with catalog errors.
	 */
	private record Outcome(OutcomeStatus status, List<ImportError> errors) {

		static final Outcome APPLIED = new Outcome(OutcomeStatus.APPLIED, List.of());

		static final Outcome STALE = new Outcome(OutcomeStatus.STALE, List.of());

	}

	private enum OutcomeStatus {
		APPLIED, STALE, REJECTED
	}

	/**
	 * Renders the preview of {@code pending}, built as {@code preview}, with
	 * an optional {@code notice} shown above it.
	 */
	private String renderPreview(Model model, PendingImport pending, ImportPreview.Result preview, String notice) {
		model.addAttribute("token", pending.token());
		model.addAttribute("rows", preview.rows());
		model.addAttribute("totals", preview.totals());
		model.addAttribute("notice", notice);
		return PREVIEW_VIEW;
	}

	private String renderErrors(Model model, List<ImportError> errors, Locale locale) {
		List<String> shown = errors.stream()
			.limit(MAX_ERRORS_SHOWN)
			.map(error -> messageSource.getMessage(error.messageKey(), resolveArgs(error, locale), locale))
			.toList();
		model.addAttribute("errors", shown);
		if (errors.size() > MAX_ERRORS_SHOWN) {
			model.addAttribute("moreErrors", messageSource.getMessage("partsImport.error.moreErrors",
					new Object[] { errors.size() - MAX_ERRORS_SHOWN }, locale));
		}
		return VIEW;
	}

	private Object[] resolveArgs(ImportError error, Locale locale) {
		return error.args().stream()
			.map(arg -> arg instanceof PartsCsvReader.MissingColumns missing ? missing.columns()
				.stream()
				.map(column -> messageSource.getMessage(column.labelKey(), null, locale))
				.collect(Collectors.joining(", ")) : arg)
			.toArray();
	}

}

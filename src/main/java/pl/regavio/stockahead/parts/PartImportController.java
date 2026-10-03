package pl.regavio.stockahead.parts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;

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

	PartImportController(PartRepository partRepository, MessageSource messageSource) {
		this.partRepository = partRepository;
		this.messageSource = messageSource;
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

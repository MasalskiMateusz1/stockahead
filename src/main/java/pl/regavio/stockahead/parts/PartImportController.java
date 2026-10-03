package pl.regavio.stockahead.parts;

import java.io.IOException;
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

/**
 * Manager-only parts CSV import: the upload page documenting the file format,
 * and the upload itself, which reads the file ({@link PartsCsvReader}),
 * validates and merges its rows ({@link PartsImportFile}) and re-renders the
 * page with every error found, at most {@value #MAX_ERRORS_SHOWN} of them
 * listed.
 * <p>
 * The {@value #MAX_FILE_BYTES}-byte limit is checked here, not by the
 * multipart limits (set higher, at 10 MB): a multipart size failure happens
 * before the controller runs, so the CSRF filter would answer 403 or the
 * container would reset the connection instead of showing a message.
 */
@Controller
public class PartImportController {

	static final String VIEW = "parts-import";

	static final long MAX_FILE_BYTES = 1024 * 1024;

	static final int MAX_ERRORS_SHOWN = 50;

	private final MessageSource messageSource;

	PartImportController(MessageSource messageSource) {
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
			Locale locale) {
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
		if (importFile.hasErrors()) {
			return renderErrors(model, importFile.errors(), locale);
		}

		// Placeholder until the catalog preview replaces it.
		model.addAttribute("validMessage", messageSource.getMessage("partsImport.valid",
				new Object[] { importFile.lines().size() }, locale));
		return VIEW;
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

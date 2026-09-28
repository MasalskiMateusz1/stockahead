package pl.regavio.stockahead.projects;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.parts.Part;
import pl.regavio.stockahead.parts.PartRepository;

/**
 * Manager-only BOM editing on a project's detail page: add a line (active
 * part + quantity per unit), change a line's quantity, remove a line.
 * Errors re-render {@code project-detail} through {@link ProjectDetailModel}
 * with HTTP 200. The DB {@code UNIQUE (project_id, part_id)} constraint is
 * the only duplicate-line check. Edits are allowed on inactive projects, and
 * a line's quantity may be changed even when its part is inactive.
 */
@Controller
public class ProjectBomController {

	private final ProjectRepository projectRepository;

	private final PartRepository partRepository;

	private final ProjectDetailModel projectDetailModel;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	ProjectBomController(ProjectRepository projectRepository, PartRepository partRepository,
			ProjectDetailModel projectDetailModel, PlatformTransactionManager transactionManager,
			MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.partRepository = partRepository;
		this.projectDetailModel = projectDetailModel;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@PostMapping("/projects/{id}/bom")
	@PreAuthorize("hasRole('MANAGER')")
	public String addLine(
			@PathVariable Long id,
			@RequestParam(required = false) String partId,
			@RequestParam(required = false) String quantityPerUnit,
			Model model,
			Locale locale) {
		if (!projectRepository.existsById(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		String quantityError = validateQuantity(quantityPerUnit, locale);
		if (quantityError != null) {
			return renderAddLineError(model, id, quantityError, partId, quantityPerUnit);
		}
		int parsedQuantity = Integer.parseInt(quantityPerUnit.trim());

		Long parsedPartId = parsePartId(partId);
		if (parsedPartId == null) {
			return renderAddLineError(model, id,
					messageSource.getMessage("projects.bom.error.partUnavailable", null, locale), partId,
					quantityPerUnit);
		}

		boolean added;
		try {
			added = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
				Project project = projectRepository.findById(id)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				Part part = partRepository.findById(parsedPartId).filter(Part::isActive).orElse(null);
				if (part == null) {
					return false;
				}
				BomLine line = new BomLine();
				line.setPart(part);
				line.setQuantityPerUnit(parsedQuantity);
				project.addBomLine(line);
				projectRepository.saveAndFlush(project);
				return true;
			}));
		}
		catch (DataIntegrityViolationException ex) {
			return renderAddLineError(model, id,
					messageSource.getMessage("projects.bom.error.duplicatePart", null, locale), partId,
					quantityPerUnit);
		}

		if (!added) {
			return renderAddLineError(model, id,
					messageSource.getMessage("projects.bom.error.partUnavailable", null, locale), partId,
					quantityPerUnit);
		}

		return "redirect:/projects/" + id;
	}

	@PostMapping("/projects/{id}/bom/{lineId}")
	@PreAuthorize("hasRole('MANAGER')")
	public String changeQuantity(
			@PathVariable Long id,
			@PathVariable Long lineId,
			@RequestParam(required = false) String quantityPerUnit,
			Model model,
			Locale locale) {
		transactionTemplate.executeWithoutResult(status -> findLine(id, lineId));

		String quantityError = validateQuantity(quantityPerUnit, locale);
		if (quantityError != null) {
			return renderChangeQuantityError(model, id, lineId, quantityError, quantityPerUnit);
		}
		int parsedQuantity = Integer.parseInt(quantityPerUnit.trim());

		try {
			transactionTemplate.executeWithoutResult(status -> {
				BomLine line = findLine(id, lineId);
				line.setQuantityPerUnit(parsedQuantity);
				projectRepository.saveAndFlush(line.getProject());
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderChangeQuantityError(model, id, lineId,
					messageSource.getMessage("projects.bom.error.saveFailed", null, locale), quantityPerUnit);
		}

		return "redirect:/projects/" + id;
	}

	@PostMapping("/projects/{id}/bom/{lineId}/delete")
	@PreAuthorize("hasRole('MANAGER')")
	public String removeLine(@PathVariable Long id, @PathVariable Long lineId) {
		transactionTemplate.executeWithoutResult(status -> {
			BomLine line = findLine(id, lineId);
			Project project = line.getProject();
			project.removeBomLine(line);
			projectRepository.saveAndFlush(project);
		});
		return "redirect:/projects/" + id;
	}

	/**
	 * Loads the line {@code lineId} of project {@code projectId} inside the
	 * caller's transaction. Unknown project, unknown line, or a line of
	 * another project is 404.
	 */
	private BomLine findLine(Long projectId, Long lineId) {
		Project project = projectRepository.findById(projectId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return project.getBomLines().stream()
			.filter(line -> line.getId().equals(lineId))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/**
	 * Returns the localized error for a raw quantity-per-unit value, or
	 * {@code null} when it parses to an integer of at least 1.
	 */
	private String validateQuantity(String rawQuantity, Locale locale) {
		int parsed;
		try {
			parsed = Integer.parseInt(rawQuantity == null ? "" : rawQuantity.trim());
		}
		catch (NumberFormatException ex) {
			return messageSource.getMessage("projects.bom.error.quantityNotInteger", null, locale);
		}
		if (parsed < 1) {
			return messageSource.getMessage("projects.bom.error.quantityNotPositive", null, locale);
		}
		return null;
	}

	private static Long parsePartId(String rawPartId) {
		if (rawPartId == null) {
			return null;
		}
		try {
			return Long.valueOf(rawPartId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String renderAddLineError(Model model, Long id, String error, String partId, String quantityPerUnit) {
		projectDetailModel.render(model, id, true, error);
		model.addAttribute("partId", partId);
		model.addAttribute("quantityPerUnit", quantityPerUnit);
		return ProjectDetailModel.VIEW;
	}

	private String renderChangeQuantityError(Model model, Long id, Long lineId, String error,
			String quantityPerUnit) {
		projectDetailModel.render(model, id, true, error);
		model.addAttribute("errorLineId", lineId);
		model.addAttribute("quantityPerUnit", quantityPerUnit);
		return ProjectDetailModel.VIEW;
	}

}

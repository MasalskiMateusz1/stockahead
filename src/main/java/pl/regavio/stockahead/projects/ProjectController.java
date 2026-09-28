package pl.regavio.stockahead.projects;

import java.time.Instant;
import java.util.Locale;

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

/**
 * Project list and detail screens, open to any authenticated user, plus
 * manager-only create/rename/deactivate/reactivate actions. Renaming is a
 * form on the detail page, next to the BOM and links, not a separate screen.
 * {@code showInactive} is honored only for managers, and an inactive
 * project's detail page is 404 for a technician. The DB {@code UNIQUE}
 * constraint on {@code projects.name} is the only duplicate-name check.
 */
@Controller
public class ProjectController {

	private static final int MAX_NAME_LENGTH = 255;

	private final ProjectRepository projectRepository;

	private final ProjectDetailModel projectDetailModel;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	ProjectController(ProjectRepository projectRepository, ProjectDetailModel projectDetailModel,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.projectDetailModel = projectDetailModel;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@GetMapping("/projects")
	public String list(
			@RequestParam(name = "showInactive", required = false, defaultValue = "false") boolean showInactive,
			Authentication authentication,
			Model model) {
		boolean isManager = isManager(authentication);
		boolean effectiveShowInactive = isManager && showInactive;

		model.addAttribute("projects", projectRepository.list(effectiveShowInactive));
		model.addAttribute("showInactive", effectiveShowInactive);
		model.addAttribute("isManager", isManager);
		return "projects-list";
	}

	@GetMapping("/projects/{id}")
	public String detail(@PathVariable Long id, Authentication authentication, Model model) {
		return projectDetailModel.render(model, id, isManager(authentication));
	}

	@GetMapping("/projects/new")
	@PreAuthorize("hasRole('MANAGER')")
	public String newForm() {
		return "projects-new";
	}

	@PostMapping("/projects")
	@PreAuthorize("hasRole('MANAGER')")
	public String create(@RequestParam String name, Model model, Locale locale) {
		String trimmedName = name == null ? "" : name.trim();
		String validationError = validateName(trimmedName, locale);
		if (validationError != null) {
			return renderNewProjectError(model, validationError, name);
		}

		Long newId;
		try {
			newId = transactionTemplate.execute(status -> {
				Project project = new Project();
				project.setName(trimmedName);
				project.setActive(true);
				project.setCreatedAt(Instant.now());
				return projectRepository.saveAndFlush(project).getId();
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderNewProjectError(model,
					messageSource.getMessage("projects.error.duplicateName", null, locale), name);
		}

		return "redirect:/projects/" + newId;
	}

	@PostMapping("/projects/{id}")
	@PreAuthorize("hasRole('MANAGER')")
	public String rename(@PathVariable Long id, @RequestParam String name, Model model, Locale locale) {
		if (!projectRepository.existsById(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		String trimmedName = name == null ? "" : name.trim();
		String validationError = validateName(trimmedName, locale);
		if (validationError != null) {
			return renderEditProjectError(model, id, validationError, name);
		}

		try {
			transactionTemplate.executeWithoutResult(status -> {
				Project project = projectRepository.findById(id)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				project.setName(trimmedName);
				projectRepository.saveAndFlush(project);
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderEditProjectError(model, id,
					messageSource.getMessage("projects.error.duplicateName", null, locale), name);
		}

		return "redirect:/projects/" + id;
	}

	@PostMapping("/projects/{id}/deactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String deactivate(@PathVariable Long id) {
		setActive(id, false);
		return "redirect:/projects";
	}

	@PostMapping("/projects/{id}/reactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String reactivate(@PathVariable Long id) {
		setActive(id, true);
		return "redirect:/projects?showInactive=true";
	}

	private void setActive(Long id, boolean active) {
		transactionTemplate.executeWithoutResult(status -> {
			Project project = projectRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			project.setActive(active);
			projectRepository.saveAndFlush(project);
		});
	}

	private static boolean isManager(Authentication authentication) {
		return authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch("ROLE_MANAGER"::equals);
	}

	/**
	 * Returns the localized validation error for an already-trimmed name, or
	 * {@code null} when the name is acceptable.
	 */
	private String validateName(String trimmedName, Locale locale) {
		if (trimmedName.isEmpty()) {
			return messageSource.getMessage("projects.error.nameRequired", null, locale);
		}
		if (trimmedName.length() > MAX_NAME_LENGTH) {
			return messageSource.getMessage("projects.error.nameTooLong", new Object[] { MAX_NAME_LENGTH }, locale);
		}
		return null;
	}

	private String renderNewProjectError(Model model, String error, String name) {
		model.addAttribute("error", error);
		model.addAttribute("name", name);
		return "projects-new";
	}

	/**
	 * Re-renders the detail page, where the rename form lives, with the error
	 * and the submitted name. Routes calling this are manager-only.
	 */
	private String renderEditProjectError(Model model, Long id, String error, String name) {
		projectDetailModel.render(model, id, true, error);
		model.addAttribute("name", name);
		return ProjectDetailModel.VIEW;
	}

}

package pl.regavio.stockahead.projects;

import java.net.URI;
import java.net.URISyntaxException;
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

/**
 * Manager-only documentation links on a project's detail page: add a link
 * (URL + optional label), remove a link. The URL is whitelisted server-side
 * to {@code http}/{@code https} with a non-empty host, because
 * {@code th:href} does not filter {@code javascript:} URLs; the DB
 * {@code CHECK (url ~* '^https?://')} is the backstop. Errors re-render
 * {@code project-detail} through {@link ProjectDetailModel} with HTTP 200.
 */
@Controller
public class ProjectLinkController {

	private static final int MAX_URL_LENGTH = 2048;

	private static final int MAX_LABEL_LENGTH = 255;

	private final ProjectRepository projectRepository;

	private final ProjectDetailModel projectDetailModel;

	private final TransactionTemplate transactionTemplate;

	private final MessageSource messageSource;

	ProjectLinkController(ProjectRepository projectRepository, ProjectDetailModel projectDetailModel,
			PlatformTransactionManager transactionManager, MessageSource messageSource) {
		this.projectRepository = projectRepository;
		this.projectDetailModel = projectDetailModel;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.messageSource = messageSource;
	}

	@PostMapping("/projects/{id}/links")
	@PreAuthorize("hasRole('MANAGER')")
	public String addLink(
			@PathVariable Long id,
			@RequestParam(required = false) String url,
			@RequestParam(required = false) String label,
			Model model,
			Locale locale) {
		if (!projectRepository.existsById(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		String trimmedUrl = url == null ? "" : url.trim();
		String urlError = validateUrl(trimmedUrl, locale);
		if (urlError != null) {
			return renderAddLinkError(model, id, urlError, url, label);
		}

		String trimmedLabel = label == null ? "" : label.trim();
		if (trimmedLabel.length() > MAX_LABEL_LENGTH) {
			return renderAddLinkError(model, id, messageSource.getMessage("projects.links.error.labelTooLong",
					new Object[] { MAX_LABEL_LENGTH }, locale), url, label);
		}
		String storedLabel = trimmedLabel.isEmpty() ? null : trimmedLabel;

		try {
			transactionTemplate.executeWithoutResult(status -> {
				Project project = projectRepository.findById(id)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				ProjectLink link = new ProjectLink();
				link.setUrl(trimmedUrl);
				link.setLabel(storedLabel);
				project.addLink(link);
				projectRepository.saveAndFlush(project);
			});
		}
		catch (DataIntegrityViolationException ex) {
			return renderAddLinkError(model, id,
					messageSource.getMessage("projects.links.error.saveFailed", null, locale), url, label);
		}

		return "redirect:/projects/" + id;
	}

	@PostMapping("/projects/{id}/links/{linkId}/delete")
	@PreAuthorize("hasRole('MANAGER')")
	public String removeLink(@PathVariable Long id, @PathVariable Long linkId) {
		transactionTemplate.executeWithoutResult(status -> {
			ProjectLink link = findLink(id, linkId);
			Project project = link.getProject();
			project.removeLink(link);
			projectRepository.saveAndFlush(project);
		});
		return "redirect:/projects/" + id;
	}

	/**
	 * Loads the link {@code linkId} of project {@code projectId} inside the
	 * caller's transaction. Unknown project, unknown link, or a link of
	 * another project is 404.
	 */
	private ProjectLink findLink(Long projectId, Long linkId) {
		Project project = projectRepository.findById(projectId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return project.getLinks().stream()
			.filter(link -> link.getId().equals(linkId))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/**
	 * Returns the localized error for a trimmed URL, or {@code null} when it
	 * is at most {@value #MAX_URL_LENGTH} characters, parses as a
	 * {@link URI}, has scheme {@code http} or {@code https}
	 * (case-insensitive) and a non-empty host.
	 */
	private String validateUrl(String trimmedUrl, Locale locale) {
		if (trimmedUrl.isEmpty()) {
			return messageSource.getMessage("projects.links.error.urlRequired", null, locale);
		}
		if (trimmedUrl.length() > MAX_URL_LENGTH) {
			return messageSource.getMessage("projects.links.error.urlTooLong", new Object[] { MAX_URL_LENGTH },
					locale);
		}
		if (!isHttpUrlWithHost(trimmedUrl)) {
			return messageSource.getMessage("projects.links.error.urlInvalid", null, locale);
		}
		return null;
	}

	private static boolean isHttpUrlWithHost(String candidate) {
		URI uri;
		try {
			uri = new URI(candidate);
		}
		catch (URISyntaxException ex) {
			return false;
		}
		String scheme = uri.getScheme();
		if (scheme == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return false;
		}
		String host = uri.getHost();
		return host != null && !host.isEmpty();
	}

	private String renderAddLinkError(Model model, Long id, String error, String url, String label) {
		projectDetailModel.render(model, id, true, error);
		model.addAttribute("linkUrl", url);
		model.addAttribute("linkLabel", label);
		return ProjectDetailModel.VIEW;
	}

}

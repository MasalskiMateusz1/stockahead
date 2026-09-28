package pl.regavio.stockahead.projects;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.parts.PartRepository;

/**
 * Fills the {@code project-detail} page model so every controller that
 * renders the detail page (project lifecycle, BOM, links) does it the same
 * way. Always re-reads inside its own read-only transaction and copies the
 * data into plain view records, never reusing the caller's entities. With
 * open-in-view enabled (the default) that read shares the request-bound
 * EntityManager with the caller's write; calling it after a rolled-back write
 * is safe because {@code JpaTransactionManager} clears that EntityManager on
 * rollback, so the failed changes are not seen. Callers add
 * their own submitted form values to the model after calling
 * {@link #render}.
 */
@Component
class ProjectDetailModel {

	static final String VIEW = "project-detail";

	private final ProjectRepository projectRepository;

	private final PartRepository partRepository;

	private final TransactionTemplate readTransaction;

	ProjectDetailModel(ProjectRepository projectRepository, PartRepository partRepository,
			PlatformTransactionManager transactionManager) {
		this.projectRepository = projectRepository;
		this.partRepository = partRepository;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * Populates the detail page for {@code projectId}. An unknown project is
	 * 404; an inactive project is 404 unless {@code isManager}.
	 * @return the {@code project-detail} view name
	 */
	String render(Model model, Long projectId, boolean isManager) {
		return render(model, projectId, isManager, null);
	}

	/**
	 * Same as {@link #render(Model, Long, boolean)}, with a page-level error
	 * message shown at the top of the page ({@code null} for none).
	 */
	String render(Model model, Long projectId, boolean isManager, String error) {
		DetailData data = readTransaction.execute(status -> load(projectId, isManager));
		model.addAttribute("project", data.project());
		model.addAttribute("lines", data.lines());
		model.addAttribute("links", data.links());
		model.addAttribute("activeParts", data.activeParts());
		model.addAttribute("isManager", isManager);
		if (error != null) {
			model.addAttribute("error", error);
		}
		return VIEW;
	}

	private DetailData load(Long projectId, boolean isManager) {
		Project project = projectRepository.findById(projectId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (!project.isActive() && !isManager) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		List<LineView> lines = project.getBomLines().stream()
			.map(line -> new LineView(line.getId(), line.getPart().getId(), line.getPart().getName(),
					line.getPart().isActive(), line.getQuantityPerUnit()))
			.sorted(Comparator.comparing(LineView::partName))
			.toList();
		List<LinkView> links = project.getLinks().stream()
			.map(link -> new LinkView(link.getId(), link.getUrl(), link.getLabel()))
			.sorted(Comparator.comparing(LinkView::id))
			.toList();
		// Only the manager's add-line form offers parts; skip the catalog query otherwise.
		List<PartOption> activeParts = !isManager ? List.of()
				: partRepository.search("", false).stream()
					.map(part -> new PartOption(part.getId(), part.getName()))
					.toList();
		return new DetailData(new ProjectView(project.getId(), project.getName(), project.isActive()), lines,
				links, activeParts);
	}

	record ProjectView(Long id, String name, boolean active) {
	}

	record LineView(Long id, Long partId, String partName, boolean partActive, int quantityPerUnit) {
	}

	record LinkView(Long id, String url, String label) {
	}

	record PartOption(Long id, String name) {
	}

	private record DetailData(ProjectView project, List<LineView> lines, List<LinkView> links,
			List<PartOption> activeParts) {
	}

}

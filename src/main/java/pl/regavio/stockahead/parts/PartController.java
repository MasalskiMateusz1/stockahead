package pl.regavio.stockahead.parts;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Parts catalog search/browse screen, open to any authenticated user.
 * {@code showInactive} is honored only for managers; a technician always
 * sees the active catalog regardless of the query parameter.
 */
@Controller
public class PartController {

	private final PartRepository partRepository;

	public PartController(PartRepository partRepository) {
		this.partRepository = partRepository;
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

		model.addAttribute("parts", partRepository.search(q, effectiveShowInactive));
		model.addAttribute("q", q);
		model.addAttribute("showInactive", effectiveShowInactive);
		model.addAttribute("isManager", isManager);
		return "parts-list";
	}

}

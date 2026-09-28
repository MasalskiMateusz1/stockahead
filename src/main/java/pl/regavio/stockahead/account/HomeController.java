package pl.regavio.stockahead.account;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Login page and the post-login dashboard stub. The dashboard is a
 * placeholder replaced by later slices — it only proves the current
 * account's role is visible and that logout works.
 */
@Controller
public class HomeController {

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/")
	public String dashboard(Authentication authentication, Model model) {
		boolean isManager = authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch("ROLE_MANAGER"::equals);

		model.addAttribute("email", authentication.getName());
		model.addAttribute("role", isManager ? "MANAGER" : "TECHNICIAN");
		return "dashboard";
	}

}

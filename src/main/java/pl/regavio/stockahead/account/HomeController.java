package pl.regavio.stockahead.account;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Login page and the post-login landing: the operator is sent to the company
 * list, everyone else gets the warehouse dashboard. The signed-in account's
 * email and role are shown by the shared topbar fragment via Spring Security
 * dialect attributes, so the dashboard needs no model attributes.
 */
@Controller
public class HomeController {

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/")
	public String dashboard(Authentication authentication) {
		if (AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_OPERATOR")) {
			return "redirect:/operator/companies";
		}
		return "dashboard";
	}

}

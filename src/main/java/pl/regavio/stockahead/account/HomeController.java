package pl.regavio.stockahead.account;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Login page and the post-login dashboard. The signed-in account's email and
 * role are shown by the shared topbar fragment via Spring Security dialect
 * attributes, so the dashboard needs no model attributes.
 */
@Controller
public class HomeController {

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/")
	public String dashboard() {
		return "dashboard";
	}

}

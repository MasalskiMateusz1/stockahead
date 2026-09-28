package pl.regavio.stockahead.account;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference route proving the {@code @PreAuthorize} pattern that the
 * remaining roadmap slices (S-01…S-10) build on. Exists purely to be
 * tested — no business logic.
 */
@RestController
public class ManagerPingController {

	@GetMapping("/manager/ping")
	@PreAuthorize("hasRole('MANAGER')")
	public String ping() {
		return "pong";
	}

}

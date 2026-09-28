package pl.regavio.stockahead.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * First-run setup screen: creates the initial MANAGER account, guarded by an
 * out-of-band token from the environment. Disables itself once a manager
 * exists.
 */
@Controller
public class SetupController {

	private final AccountRepository accountRepository;

	private final PasswordEncoder passwordEncoder;

	private final String setupToken;

	private final MessageSource messageSource;

	public SetupController(AccountRepository accountRepository, PasswordEncoder passwordEncoder,
			@Value("${app.setup-token}") String setupToken, MessageSource messageSource) {
		this.accountRepository = accountRepository;
		this.passwordEncoder = passwordEncoder;
		this.setupToken = setupToken;
		this.messageSource = messageSource;
	}

	@GetMapping("/setup")
	public String showSetupForm() {
		if (accountRepository.existsByRole(Role.MANAGER)) {
			return "redirect:/login";
		}
		return "setup";
	}

	@PostMapping("/setup")
	public String createManagerAccount(@RequestParam String email, @RequestParam String password,
			@RequestParam String confirmPassword, @RequestParam String token, Model model, Locale locale) {
		if (!isTokenValid(token)) {
			model.addAttribute("email", email);
			model.addAttribute("error", messageSource.getMessage("setup.error.invalidToken", null, locale));
			return "setup";
		}

		if (!password.equals(confirmPassword)) {
			model.addAttribute("email", email);
			model.addAttribute("error", messageSource.getMessage("setup.error.passwordMismatch", null, locale));
			return "setup";
		}

		// Re-check right before creating: a second concurrent submission may have
		// already created the manager account between the GET render and this POST.
		if (accountRepository.existsByRole(Role.MANAGER)) {
			return "redirect:/login";
		}

		Account account = new Account();
		account.setEmail(Emails.canonical(email));
		account.setPasswordHash(passwordEncoder.encode(password));
		account.setRole(Role.MANAGER);
		account.setCreatedAt(Instant.now());

		try {
			accountRepository.save(account);
		}
		catch (DataIntegrityViolationException ex) {
			model.addAttribute("email", email);
			model.addAttribute("error", messageSource.getMessage("setup.error.accountCreationFailed", null, locale));
			return "setup";
		}

		return "redirect:/login?setup";
	}

	private boolean isTokenValid(String submittedToken) {
		byte[] submitted = submittedToken.getBytes(StandardCharsets.UTF_8);
		byte[] expected = setupToken.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(submitted, expected);
	}

}

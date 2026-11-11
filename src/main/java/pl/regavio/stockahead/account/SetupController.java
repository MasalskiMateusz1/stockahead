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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * First-run setup screen: creates the single OPERATOR account, which belongs
 * to no company, guarded by an out-of-band token from the environment. The
 * email and password follow the same rules as every account-creating form
 * ({@link AccountCredentials}). Setup is closed exactly when an operator
 * exists.
 */
@Controller
public class SetupController {

	/**
	 * First key of the setup-only {@code pg_advisory_xact_lock(int, int)}
	 * ("SETU"). It serializes concurrent setup submissions so the losing one
	 * sees the winner's operator and creates nothing. Public registration must
	 * not reuse it: many companies are allowed there.
	 */
	private static final int SETUP_LOCK_NAMESPACE = 0x53455455;

	private static final int SETUP_LOCK_KEY = 0;

	private final AccountRepository accountRepository;

	private final PasswordEncoder passwordEncoder;

	private final String setupToken;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	public SetupController(AccountRepository accountRepository, PasswordEncoder passwordEncoder,
			@Value("${app.setup-token}") String setupToken, MessageSource messageSource,
			PlatformTransactionManager transactionManager) {
		this.accountRepository = accountRepository;
		this.passwordEncoder = passwordEncoder;
		this.setupToken = setupToken;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/setup")
	public String showSetupForm() {
		if (accountRepository.existsByRole(Role.OPERATOR)) {
			return "redirect:/login";
		}
		return "setup";
	}

	@PostMapping("/setup")
	public String createOperatorAccount(@RequestParam String email, @RequestParam String password,
			@RequestParam String confirmPassword, @RequestParam String token, Model model, Locale locale) {
		if (!isTokenValid(token)) {
			return renderError(model, email, "setup.error.invalidToken", null, locale);
		}

		AccountCredentials.Result credentials = AccountCredentials.validate(email, password, confirmPassword);
		if (!credentials.isValid()) {
			return renderError(model, email, credentials.errorKey(), credentials.errorArguments(), locale);
		}

		// Re-check right before creating: a second concurrent submission may have
		// already created the operator between the GET render and this POST.
		if (accountRepository.existsByRole(Role.OPERATOR)) {
			return "redirect:/login";
		}

		boolean created;
		try {
			created = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
				// Serialize concurrent setups, then re-check under the lock: the losing
				// submission must see the winner's operator and create nothing.
				accountRepository.lockAdvisory(SETUP_LOCK_NAMESPACE, SETUP_LOCK_KEY);
				if (accountRepository.existsByRole(Role.OPERATOR)) {
					return false;
				}

				Account account = new Account();
				account.setEmail(credentials.canonicalEmail());
				account.setPasswordHash(passwordEncoder.encode(password));
				account.setRole(Role.OPERATOR);
				account.setActive(true);
				account.setCreatedAt(Instant.now());
				accountRepository.saveAndFlush(account);
				return true;
			}));
		}
		catch (DataIntegrityViolationException ex) {
			// No operator exists under the lock, so the only unique index that can
			// fire is the canonical-email one: the email belongs to a company account.
			return renderError(model, email, "accounts.error.duplicateEmail", null, locale);
		}

		if (!created) {
			return "redirect:/login";
		}
		return "redirect:/login?setup";
	}

	private String renderError(Model model, String email, String messageKey, Object[] arguments, Locale locale) {
		model.addAttribute("email", email);
		model.addAttribute("error", messageSource.getMessage(messageKey, arguments, locale));
		return "setup";
	}

	private boolean isTokenValid(String submittedToken) {
		byte[] submitted = submittedToken.getBytes(StandardCharsets.UTF_8);
		byte[] expected = setupToken.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(submitted, expected);
	}

}

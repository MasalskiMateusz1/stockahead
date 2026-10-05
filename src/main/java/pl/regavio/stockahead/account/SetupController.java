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
 * First-run setup screen: creates the first company together with its
 * initial MANAGER account, guarded by an out-of-band token from the
 * environment. Disables itself once a manager exists.
 */
@Controller
public class SetupController {

	private static final int MAX_COMPANY_NAME_LENGTH = 255;

	/**
	 * First key of the setup-only {@code pg_advisory_xact_lock(int, int)}
	 * ("SETU"). It serializes concurrent setup submissions now that the
	 * database allows more than one manager. Public registration must not
	 * reuse it: many companies are allowed there.
	 */
	private static final int SETUP_LOCK_NAMESPACE = 0x53455455;

	private static final int SETUP_LOCK_KEY = 0;

	private final AccountRepository accountRepository;

	private final CompanyRepository companyRepository;

	private final PasswordEncoder passwordEncoder;

	private final String setupToken;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	public SetupController(AccountRepository accountRepository, CompanyRepository companyRepository,
			PasswordEncoder passwordEncoder, @Value("${app.setup-token}") String setupToken,
			MessageSource messageSource, PlatformTransactionManager transactionManager) {
		this.accountRepository = accountRepository;
		this.companyRepository = companyRepository;
		this.passwordEncoder = passwordEncoder;
		this.setupToken = setupToken;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/setup")
	public String showSetupForm() {
		if (accountRepository.existsByRole(Role.MANAGER)) {
			return "redirect:/login";
		}
		return "setup";
	}

	@PostMapping("/setup")
	public String createManagerAccount(@RequestParam(defaultValue = "") String companyName,
			@RequestParam String email, @RequestParam String password, @RequestParam String confirmPassword,
			@RequestParam String token, Model model, Locale locale) {
		if (!isTokenValid(token)) {
			return renderError(model, email, companyName, "setup.error.invalidToken", locale);
		}

		String trimmedCompanyName = companyName.strip();
		if (trimmedCompanyName.isEmpty()) {
			return renderError(model, email, companyName, "setup.error.companyNameRequired", locale);
		}
		if (trimmedCompanyName.length() > MAX_COMPANY_NAME_LENGTH) {
			return renderError(model, email, companyName, "setup.error.companyNameTooLong", locale);
		}

		if (!password.equals(confirmPassword)) {
			return renderError(model, email, companyName, "setup.error.passwordMismatch", locale);
		}

		// Re-check right before creating: a second concurrent submission may have
		// already created the manager account between the GET render and this POST.
		if (accountRepository.existsByRole(Role.MANAGER)) {
			return "redirect:/login";
		}

		boolean created;
		try {
			created = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
				// Serialize concurrent setups, then re-check under the lock: the losing
				// submission must see the winner's manager and create nothing.
				accountRepository.lockAdvisory(SETUP_LOCK_NAMESPACE, SETUP_LOCK_KEY);
				if (accountRepository.existsByRole(Role.MANAGER)) {
					return false;
				}

				Company company = new Company();
				company.setName(trimmedCompanyName);
				company.setStatus(CompanyStatus.ACTIVE);
				company.setCreatedAt(Instant.now());
				companyRepository.save(company);

				Account account = new Account();
				account.setEmail(Emails.canonical(email));
				account.setPasswordHash(passwordEncoder.encode(password));
				account.setRole(Role.MANAGER);
				account.setCreatedAt(Instant.now());
				account.setCompany(company);
				accountRepository.saveAndFlush(account);
				return true;
			}));
		}
		catch (DataIntegrityViolationException ex) {
			return renderError(model, email, companyName, "setup.error.accountCreationFailed", locale);
		}

		if (!created) {
			return "redirect:/login";
		}
		return "redirect:/login?setup";
	}

	private String renderError(Model model, String email, String companyName, String messageKey, Locale locale) {
		model.addAttribute("email", email);
		model.addAttribute("companyName", companyName);
		model.addAttribute("error", messageSource.getMessage(messageKey, null, locale));
		return "setup";
	}

	private boolean isTokenValid(String submittedToken) {
		byte[] submitted = submittedToken.getBytes(StandardCharsets.UTF_8);
		byte[] expected = setupToken.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(submitted, expected);
	}

}

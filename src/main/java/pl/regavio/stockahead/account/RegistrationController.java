package pl.regavio.stockahead.account;

import java.time.Instant;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Public company registration: an anonymous visitor creates a
 * {@link CompanyStatus#PENDING} company and becomes its active manager in one
 * transaction, then is sent to log in. The company name is checked first,
 * then the email and password with the same rules as every account-creating
 * form ({@link AccountCredentials}). Company names need not be unique; the
 * email is globally unique through the canonical-email index, so a taken
 * email surfaces as a constraint violation on insert and rolls the company
 * back with it. The page never names an existing company.
 */
@Controller
public class RegistrationController {

	public static final int MAX_COMPANY_NAME_LENGTH = 255;

	private final CompanyRepository companyRepository;

	private final AccountRepository accountRepository;

	private final PasswordEncoder passwordEncoder;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	public RegistrationController(CompanyRepository companyRepository, AccountRepository accountRepository,
			PasswordEncoder passwordEncoder, MessageSource messageSource,
			PlatformTransactionManager transactionManager) {
		this.companyRepository = companyRepository;
		this.accountRepository = accountRepository;
		this.passwordEncoder = passwordEncoder;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/register")
	public String showRegistrationForm(Authentication authentication) {
		if (isAuthenticated(authentication)) {
			return "redirect:/";
		}
		return "register";
	}

	@PostMapping("/register")
	public String registerCompany(@RequestParam String companyName, @RequestParam String email,
			@RequestParam String password, @RequestParam String confirmPassword, Model model, Locale locale) {
		String trimmedName = companyName.strip();
		if (trimmedName.isEmpty()) {
			return renderError(model, companyName, email, "register.error.companyNameRequired", null, locale);
		}
		if (trimmedName.length() > MAX_COMPANY_NAME_LENGTH) {
			return renderError(model, companyName, email, "register.error.companyNameTooLong",
					new Object[] { MAX_COMPANY_NAME_LENGTH }, locale);
		}

		AccountCredentials.Result credentials = AccountCredentials.validate(email, password, confirmPassword);
		if (!credentials.isValid()) {
			return renderError(model, companyName, email, credentials.errorKey(), credentials.errorArguments(),
					locale);
		}

		try {
			transactionTemplate.executeWithoutResult(status -> {
				Instant now = Instant.now();

				Company company = new Company();
				company.setName(trimmedName);
				company.setStatus(CompanyStatus.PENDING);
				company.setCreatedAt(now);
				companyRepository.save(company);

				Account account = new Account();
				account.setEmail(credentials.canonicalEmail());
				account.setPasswordHash(passwordEncoder.encode(password));
				account.setRole(Role.MANAGER);
				account.setActive(true);
				account.setCreatedAt(now);
				account.setCompany(company);
				accountRepository.saveAndFlush(account);
			});
		}
		catch (DataIntegrityViolationException ex) {
			// Company names are not unique, so the only constraint the insert can hit
			// is the global canonical-email index; the company row rolls back with it.
			return renderError(model, companyName, email, "accounts.error.duplicateEmail", null, locale);
		}

		return "redirect:/login?registered";
	}

	private String renderError(Model model, String companyName, String email, String messageKey,
			Object[] arguments, Locale locale) {
		model.addAttribute("companyName", companyName);
		model.addAttribute("email", email);
		model.addAttribute("error", messageSource.getMessage(messageKey, arguments, locale));
		return "register";
	}

	private static boolean isAuthenticated(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
	}

}

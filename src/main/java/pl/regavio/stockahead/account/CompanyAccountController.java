package pl.regavio.stockahead.account;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pl.regavio.stockahead.security.CompanyUser;

/**
 * The company accounts page: a manager lists, creates, deactivates,
 * reactivates and changes the role of the technician and manager accounts of
 * their own company. A
 * company always keeps at least one active manager; every transition that
 * could remove the last one runs under the company row lock
 * ({@link CompanyRepository#findByIdForUpdate(Long)}).
 */
@Controller
public class CompanyAccountController {

	private static final int MAX_EMAIL_LENGTH = 255;

	private static final int MIN_PASSWORD_LENGTH = 12;

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private static final String LIST_REDIRECT = "redirect:/manager/accounts";

	private final AccountRepository accountRepository;

	private final CompanyRepository companyRepository;

	private final PasswordEncoder passwordEncoder;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	public CompanyAccountController(AccountRepository accountRepository, CompanyRepository companyRepository,
			PasswordEncoder passwordEncoder, MessageSource messageSource,
			PlatformTransactionManager transactionManager) {
		this.accountRepository = accountRepository;
		this.companyRepository = companyRepository;
		this.passwordEncoder = passwordEncoder;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/manager/accounts")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(@AuthenticationPrincipal CompanyUser principal, Model model) {
		model.addAttribute("accounts", accountRepository.findByCompanyIdOrderByEmailAsc(principal.companyId()));
		return "accounts-list";
	}

	@GetMapping("/manager/accounts/new")
	@PreAuthorize("hasRole('MANAGER')")
	public String newForm(Model model) {
		model.addAttribute("role", Role.TECHNICIAN.name());
		return "accounts-new";
	}

	@PostMapping("/manager/accounts")
	@PreAuthorize("hasRole('MANAGER')")
	public String create(@RequestParam(defaultValue = "") String email,
			@RequestParam(defaultValue = "") String password,
			@RequestParam(defaultValue = "") String confirmPassword,
			@RequestParam(defaultValue = "") String role,
			@AuthenticationPrincipal CompanyUser principal, Model model, Locale locale) {
		Role parsedRole = parseRole(role);
		if (parsedRole == null) {
			return renderError(model, email, role, "accounts.error.roleInvalid", null, locale);
		}
		String canonicalEmail = Emails.canonical(email);
		if (canonicalEmail.isEmpty()) {
			return renderError(model, email, role, "accounts.error.emailRequired", null, locale);
		}
		if (canonicalEmail.length() > MAX_EMAIL_LENGTH) {
			return renderError(model, email, role, "accounts.error.emailTooLong",
					new Object[] { MAX_EMAIL_LENGTH }, locale);
		}
		if (!EMAIL_PATTERN.matcher(canonicalEmail).matches()) {
			return renderError(model, email, role, "accounts.error.emailInvalid", null, locale);
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			return renderError(model, email, role, "accounts.error.passwordTooShort",
					new Object[] { MIN_PASSWORD_LENGTH }, locale);
		}
		if (!password.equals(confirmPassword)) {
			return renderError(model, email, role, "accounts.error.passwordMismatch", null, locale);
		}

		Account account = new Account();
		account.setEmail(canonicalEmail);
		account.setPasswordHash(passwordEncoder.encode(password));
		account.setRole(parsedRole);
		account.setActive(true);
		account.setCreatedAt(Instant.now());
		account.setCompany(companyRepository.getReferenceById(principal.companyId()));
		try {
			accountRepository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException ex) {
			return renderError(model, email, role, "accounts.error.duplicateEmail", null, locale);
		}
		return LIST_REDIRECT;
	}

	@PostMapping("/manager/accounts/{id}/deactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String deactivate(@PathVariable Long id, @AuthenticationPrincipal CompanyUser principal,
			RedirectAttributes redirectAttributes, Locale locale) {
		Long companyId = principal.companyId();
		boolean refused = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			Account account = lockAndLoadGuarded(id, companyId, true);
			if (account == null) {
				return true;
			}
			if (account.isActive()) {
				account.setActive(false);
				accountRepository.saveAndFlush(account);
			}
			return false;
		}));
		if (refused) {
			redirectAttributes.addFlashAttribute("error",
					messageSource.getMessage("accounts.error.lastActiveManager", null, locale));
		}
		return LIST_REDIRECT;
	}

	/**
	 * Promotes a technician to manager or demotes a manager to technician.
	 * Demoting the company's last active manager is refused under the same
	 * company row lock as deactivation, so the two transitions serialise. An
	 * inactive account's role can be changed too: an inactive manager never
	 * counts toward the rule. The changed account's existing sessions end on
	 * their next request ({@code AccountActivityFilter}).
	 */
	@PostMapping("/manager/accounts/{id}/role")
	@PreAuthorize("hasRole('MANAGER')")
	public String changeRole(@PathVariable Long id, @RequestParam(defaultValue = "") String role,
			@AuthenticationPrincipal CompanyUser principal, RedirectAttributes redirectAttributes, Locale locale) {
		Role newRole = parseRole(role);
		if (newRole == null) {
			redirectAttributes.addFlashAttribute("error",
					messageSource.getMessage("accounts.error.roleInvalid", null, locale));
			return LIST_REDIRECT;
		}
		Long companyId = principal.companyId();
		boolean refused = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			Account account = lockAndLoadGuarded(id, companyId, newRole == Role.TECHNICIAN);
			if (account == null) {
				return true;
			}
			if (account.getRole() != newRole) {
				account.setRole(newRole);
				accountRepository.saveAndFlush(account);
			}
			return false;
		}));
		if (refused) {
			redirectAttributes.addFlashAttribute("error",
					messageSource.getMessage("accounts.error.lastActiveManager", null, locale));
		}
		return LIST_REDIRECT;
	}

	@PostMapping("/manager/accounts/{id}/reactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String reactivate(@PathVariable Long id, @AuthenticationPrincipal CompanyUser principal) {
		Long companyId = principal.companyId();
		transactionTemplate.executeWithoutResult(status -> {
			Account account = accountRepository.findByIdAndCompanyId(id, companyId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			if (!account.isActive()) {
				account.setActive(true);
				accountRepository.saveAndFlush(account);
			}
		});
		return LIST_REDIRECT;
	}

	/**
	 * The single guard of the last-active-manager rule. Locks the company row,
	 * then loads the target account scoped to the company (404 if absent) and,
	 * when {@code removesManager} is set, checks whether the transition would
	 * take away the company's last active manager: the target is an active
	 * manager and no other account of the company is one. Returns the account,
	 * or {@code null} when the transition must be refused. Must run inside a
	 * transaction; the lock is taken before any read of the company's accounts
	 * so concurrent transitions serialise.
	 */
	private Account lockAndLoadGuarded(Long id, Long companyId, boolean removesManager) {
		companyRepository.findByIdForUpdate(companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Account account = accountRepository.findByIdAndCompanyId(id, companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean lastActiveManager = removesManager && account.isActive() && account.getRole() == Role.MANAGER
				&& accountRepository.countByCompanyIdAndRoleAndActiveTrueAndIdNot(companyId, Role.MANAGER,
						account.getId()) == 0;
		return lastActiveManager ? null : account;
	}

	private static Role parseRole(String role) {
		return switch (role) {
			case "MANAGER" -> Role.MANAGER;
			case "TECHNICIAN" -> Role.TECHNICIAN;
			default -> null;
		};
	}

	private String renderError(Model model, String email, String role, String messageKey, Object[] arguments,
			Locale locale) {
		model.addAttribute("email", email);
		model.addAttribute("role", role);
		model.addAttribute("error", messageSource.getMessage(messageKey, arguments, locale));
		return "accounts-new";
	}

}

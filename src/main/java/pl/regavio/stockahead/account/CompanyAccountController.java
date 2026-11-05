package pl.regavio.stockahead.account;

import java.time.Instant;
import java.util.Locale;

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
 * company always keeps at least one active manager; every account write runs
 * under the company row lock ({@link CompanyRepository#findByIdForUpdate(Long)})
 * and re-checks the caller there, so transitions that could remove the last
 * manager, or the caller's own rights, serialise.
 */
@Controller
public class CompanyAccountController {

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
		AccountCredentials.Result credentials = AccountCredentials.validate(email, password, confirmPassword);
		if (!credentials.isValid()) {
			return renderError(model, email, role, credentials.errorKey(), credentials.errorArguments(), locale);
		}
		String canonicalEmail = credentials.canonicalEmail();

		Account account = new Account();
		account.setEmail(canonicalEmail);
		account.setPasswordHash(passwordEncoder.encode(password));
		account.setRole(parsedRole);
		account.setActive(true);
		account.setCreatedAt(Instant.now());
		account.setCompany(companyRepository.getReferenceById(principal.companyId()));
		try {
			transactionTemplate.executeWithoutResult(status -> {
				lockCompanyAsActiveManager(principal);
				accountRepository.saveAndFlush(account);
			});
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
		boolean refused = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			Account account = lockAndLoadGuarded(id, principal, true);
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
		boolean refused = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			Account account = lockAndLoadGuarded(id, principal, newRole == Role.TECHNICIAN);
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
			lockCompanyAsActiveManager(principal);
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
	private Account lockAndLoadGuarded(Long id, CompanyUser principal, boolean removesManager) {
		Long companyId = principal.companyId();
		lockCompanyAsActiveManager(principal);
		Account account = accountRepository.findByIdAndCompanyId(id, companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean lastActiveManager = removesManager && account.isActive() && account.getRole() == Role.MANAGER
				&& accountRepository.countByCompanyIdAndRoleAndActiveTrueAndIdNot(companyId, Role.MANAGER,
						account.getId()) == 0;
		return lastActiveManager ? null : account;
	}

	/**
	 * Locks the company row, then re-checks under that lock that the caller is
	 * still an active manager (403 otherwise). {@code AccountActivityFilter}
	 * checks the caller before the request reaches the controller; a manager
	 * demoted or deactivated while their request waited on the lock would
	 * otherwise still finish it with manager rights. Every account write takes
	 * this lock first, so it serialises with the transition that removed the
	 * caller's rights. Every write takes this one lock, always first, so no
	 * lock-order deadlock is possible and {@code LockRetry} is not needed.
	 * Must run inside a transaction.
	 */
	private void lockCompanyAsActiveManager(CompanyUser principal) {
		Long companyId = principal.companyId();
		companyRepository.findByIdForUpdate(companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean callerIsActiveManager = accountRepository.findByIdAndCompanyId(principal.accountId(), companyId)
			.filter(caller -> caller.isActive() && caller.getRole() == Role.MANAGER)
			.isPresent();
		if (!callerIsActiveManager) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
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

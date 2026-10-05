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

import pl.regavio.stockahead.security.CompanyUser;

@Controller
public class TechnicianAccountController {

	private static final int MAX_EMAIL_LENGTH = 255;

	private static final int MIN_PASSWORD_LENGTH = 12;

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private final AccountRepository accountRepository;

	private final CompanyRepository companyRepository;

	private final PasswordEncoder passwordEncoder;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	public TechnicianAccountController(AccountRepository accountRepository, CompanyRepository companyRepository,
			PasswordEncoder passwordEncoder, MessageSource messageSource,
			PlatformTransactionManager transactionManager) {
		this.accountRepository = accountRepository;
		this.companyRepository = companyRepository;
		this.passwordEncoder = passwordEncoder;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@GetMapping("/manager/technicians")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(Model model) {
		model.addAttribute("technicians", accountRepository.findByRoleOrderByEmailAsc(Role.TECHNICIAN));
		return "technicians-list";
	}

	@GetMapping("/manager/technicians/new")
	@PreAuthorize("hasRole('MANAGER')")
	public String newForm() {
		return "technicians-new";
	}

	@PostMapping("/manager/technicians")
	@PreAuthorize("hasRole('MANAGER')")
	public String create(@RequestParam(defaultValue = "") String email,
			@RequestParam(defaultValue = "") String password,
			@RequestParam(defaultValue = "") String confirmPassword,
			@AuthenticationPrincipal CompanyUser principal, Model model, Locale locale) {
		String canonicalEmail = Emails.canonical(email);
		if (canonicalEmail.isEmpty()) {
			return renderError(model, email, "technicians.error.emailRequired", null, locale);
		}
		if (canonicalEmail.length() > MAX_EMAIL_LENGTH) {
			return renderError(model, email, "technicians.error.emailTooLong",
					new Object[] { MAX_EMAIL_LENGTH }, locale);
		}
		if (!EMAIL_PATTERN.matcher(canonicalEmail).matches()) {
			return renderError(model, email, "technicians.error.emailInvalid", null, locale);
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			return renderError(model, email, "technicians.error.passwordTooShort",
					new Object[] { MIN_PASSWORD_LENGTH }, locale);
		}
		if (!password.equals(confirmPassword)) {
			return renderError(model, email, "technicians.error.passwordMismatch", null, locale);
		}

		Account account = new Account();
		account.setEmail(canonicalEmail);
		account.setPasswordHash(passwordEncoder.encode(password));
		account.setRole(Role.TECHNICIAN);
		account.setActive(true);
		account.setCreatedAt(Instant.now());
		account.setCompany(companyRepository.getReferenceById(principal.companyId()));
		try {
			accountRepository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException ex) {
			return renderError(model, email, "technicians.error.duplicateEmail", null, locale);
		}
		return "redirect:/manager/technicians";
	}

	@PostMapping("/manager/technicians/{id}/deactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String deactivate(@PathVariable Long id) {
		setActive(id, false);
		return "redirect:/manager/technicians";
	}

	@PostMapping("/manager/technicians/{id}/reactivate")
	@PreAuthorize("hasRole('MANAGER')")
	public String reactivate(@PathVariable Long id) {
		setActive(id, true);
		return "redirect:/manager/technicians";
	}

	private void setActive(Long id, boolean active) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = accountRepository.findById(id)
				.filter(found -> found.getRole() == Role.TECHNICIAN)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			if (account.isActive() != active) {
				account.setActive(active);
				accountRepository.saveAndFlush(account);
			}
		});
	}

	private String renderError(Model model, String email, String messageKey, Object[] arguments, Locale locale) {
		model.addAttribute("email", email);
		model.addAttribute("error", messageSource.getMessage(messageKey, arguments, locale));
		return "technicians-new";
	}

}

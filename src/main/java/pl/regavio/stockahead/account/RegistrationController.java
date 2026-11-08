package pl.regavio.stockahead.account;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
 * transaction, then is sent to log in. Every render of the form stamps the
 * session for {@link RegistrationBotCheck}. On submit the bot check runs first
 * (the honeypot must be empty and the minimum fill time must have passed since
 * the form was stamped); a failure re-renders the form with a neutral error.
 * Then the company name is checked, then the email and password with the
 * same rules as every account-creating form ({@link AccountCredentials}). Only
 * after all field validation passes does the controller reserve a slot for the
 * client IP ({@link ClientAddress}) in {@link RegistrationRateLimiter}, so typos
 * never consume quota; with no slot free the form re-renders with HTTP 429. The
 * slot is released whenever the transaction does not commit. Company names
 * need not be unique; the email is globally unique through the canonical-email index, so a taken
 * email surfaces as a constraint violation on insert and rolls the company
 * back with it. The page never names an existing company.
 */
@Controller
public class RegistrationController {

	private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

	public static final int MAX_COMPANY_NAME_LENGTH = 255;

	private final CompanyRepository companyRepository;

	private final AccountRepository accountRepository;

	private final PasswordEncoder passwordEncoder;

	private final MessageSource messageSource;

	private final TransactionTemplate transactionTemplate;

	private final RegistrationBotCheck botCheck;

	private final RegistrationRateLimiter rateLimiter;

	public RegistrationController(CompanyRepository companyRepository, AccountRepository accountRepository,
			PasswordEncoder passwordEncoder, MessageSource messageSource,
			PlatformTransactionManager transactionManager, RegistrationBotCheck botCheck,
			RegistrationRateLimiter rateLimiter) {
		this.companyRepository = companyRepository;
		this.accountRepository = accountRepository;
		this.passwordEncoder = passwordEncoder;
		this.messageSource = messageSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.botCheck = botCheck;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/register")
	public String showRegistrationForm(Authentication authentication, HttpSession session) {
		if (isAuthenticated(authentication)) {
			return "redirect:/";
		}
		botCheck.stamp(session);
		return "register";
	}

	@PostMapping("/register")
	public String registerCompany(@RequestParam String companyName, @RequestParam String email,
			@RequestParam String password, @RequestParam String confirmPassword,
			@RequestParam(name = RegistrationBotCheck.HONEYPOT_PARAMETER, required = false) String honeypot,
			HttpSession session, HttpServletRequest request, HttpServletResponse response, Model model,
			Locale locale) {
		if (!botCheck.passes(session, honeypot)) {
			log.info("Registration submission rejected by the bot check (honeypot or minimum fill time)");
			return renderError(model, session, companyName, email, "register.error.verificationFailed", null,
					locale);
		}

		String trimmedName = companyName.strip();
		if (trimmedName.isEmpty()) {
			return renderError(model, session, companyName, email, "register.error.companyNameRequired", null,
					locale);
		}
		if (trimmedName.length() > MAX_COMPANY_NAME_LENGTH) {
			return renderError(model, session, companyName, email, "register.error.companyNameTooLong",
					new Object[] { MAX_COMPANY_NAME_LENGTH }, locale);
		}

		AccountCredentials.Result credentials = AccountCredentials.validate(email, password, confirmPassword);
		if (!credentials.isValid()) {
			return renderError(model, session, companyName, email, credentials.errorKey(),
					credentials.errorArguments(), locale);
		}

		String clientIp = ClientAddress.of(request);
		Optional<RegistrationRateLimiter.Reservation> reservation = rateLimiter
				.tryReserve(ClientAddress.rateLimitKey(clientIp));
		if (reservation.isEmpty()) {
			log.info("Registration rejected by the per-IP rate limit for client {}", clientIp);
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			return renderError(model, session, companyName, email, "register.error.tooManyRegistrations", null,
					locale);
		}

		boolean committed = false;
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
			committed = true;
		}
		catch (DataIntegrityViolationException ex) {
			// Company names are not unique, so the only constraint the insert can hit
			// is the global canonical-email index; the company row rolls back with it.
			return renderError(model, session, companyName, email, "accounts.error.duplicateEmail", null, locale);
		}
		finally {
			if (!committed) {
				rateLimiter.release(reservation.get());
			}
		}

		botCheck.clear(session);
		return "redirect:/login?registered";
	}

	private String renderError(Model model, HttpSession session, String companyName, String email,
			String messageKey, Object[] arguments, Locale locale) {
		model.addAttribute("companyName", companyName);
		model.addAttribute("email", email);
		model.addAttribute("error", messageSource.getMessage(messageKey, arguments, locale));
		botCheck.stamp(session);
		return "register";
	}

	private static boolean isAuthenticated(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
	}

}

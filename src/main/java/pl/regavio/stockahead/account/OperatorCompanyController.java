package pl.regavio.stockahead.account;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The platform operator's screens: the list of every company with its
 * first-created manager's email, registration date and status; the approve
 * and reject actions on a PENDING company (reject through a confirmation
 * page); block on an ACTIVE company (through a confirmation page) and
 * unblock on a BLOCKED one. Status changes go through
 * {@link CompanyLifecycle}; an action on a company that is no longer in the
 * action's starting state changes nothing and returns to the list with an
 * info message. The operator has no route to
 * any company's warehouse data.
 */
@Controller
public class OperatorCompanyController {

	private static final String LIST_REDIRECT = "redirect:/operator/companies";

	private final CompanyRepository companyRepository;

	private final CompanyLifecycle companyLifecycle;

	private final MessageSource messageSource;

	public OperatorCompanyController(CompanyRepository companyRepository, CompanyLifecycle companyLifecycle,
			MessageSource messageSource) {
		this.companyRepository = companyRepository;
		this.companyLifecycle = companyLifecycle;
		this.messageSource = messageSource;
	}

	@GetMapping("/operator/companies")
	@PreAuthorize("hasRole('OPERATOR')")
	public String list(Model model) {
		model.addAttribute("companies", companyRepository.findAllForOperatorList());
		return "operator-companies";
	}

	@PostMapping("/operator/companies/{id}/approve")
	@PreAuthorize("hasRole('OPERATOR')")
	public String approve(@PathVariable Long id, RedirectAttributes redirectAttributes, Locale locale) {
		CompanyLifecycle.Result result;
		try {
			result = companyLifecycle.approve(id);
		}
		catch (DataIntegrityViolationException ex) {
			return actionFailed(redirectAttributes, locale);
		}
		return afterTransition(result, "operator.companies.approved", redirectAttributes, locale);
	}

	/**
	 * The reject confirmation page, naming the company and its first-created
	 * manager's email. A company that is no longer PENDING sends the operator
	 * back to the list with the status-changed message.
	 */
	@GetMapping("/operator/companies/{id}/reject")
	@PreAuthorize("hasRole('OPERATOR')")
	public String rejectForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes,
			Locale locale) {
		CompanyListRow company = companyRepository.findOperatorRowById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (company.status() != CompanyStatus.PENDING) {
			return statusChanged(redirectAttributes, locale);
		}
		model.addAttribute("company", company);
		return "operator-companies-reject";
	}

	@PostMapping("/operator/companies/{id}/reject")
	@PreAuthorize("hasRole('OPERATOR')")
	public String reject(@PathVariable Long id, RedirectAttributes redirectAttributes, Locale locale) {
		CompanyLifecycle.Result result;
		try {
			result = companyLifecycle.reject(id);
		}
		catch (DataIntegrityViolationException ex) {
			return actionFailed(redirectAttributes, locale);
		}
		return afterTransition(result, "operator.companies.rejected", redirectAttributes, locale);
	}

	/**
	 * The block confirmation page, naming the company and its first-created
	 * manager's email. A company that is not ACTIVE sends the operator back to
	 * the list with the status-changed message.
	 */
	@GetMapping("/operator/companies/{id}/block")
	@PreAuthorize("hasRole('OPERATOR')")
	public String blockForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes,
			Locale locale) {
		CompanyListRow company = companyRepository.findOperatorRowById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (company.status() != CompanyStatus.ACTIVE) {
			return statusChanged(redirectAttributes, locale);
		}
		model.addAttribute("company", company);
		return "operator-companies-block";
	}

	@PostMapping("/operator/companies/{id}/block")
	@PreAuthorize("hasRole('OPERATOR')")
	public String block(@PathVariable Long id, RedirectAttributes redirectAttributes, Locale locale) {
		CompanyLifecycle.Result result;
		try {
			result = companyLifecycle.block(id);
		}
		catch (DataIntegrityViolationException ex) {
			return actionFailed(redirectAttributes, locale);
		}
		return afterTransition(result, "operator.companies.blocked", redirectAttributes, locale);
	}

	@PostMapping("/operator/companies/{id}/unblock")
	@PreAuthorize("hasRole('OPERATOR')")
	public String unblock(@PathVariable Long id, RedirectAttributes redirectAttributes, Locale locale) {
		CompanyLifecycle.Result result;
		try {
			result = companyLifecycle.unblock(id);
		}
		catch (DataIntegrityViolationException ex) {
			return actionFailed(redirectAttributes, locale);
		}
		return afterTransition(result, "operator.companies.unblocked", redirectAttributes, locale);
	}

	private String afterTransition(CompanyLifecycle.Result result, String successKey,
			RedirectAttributes redirectAttributes, Locale locale) {
		if (result.outcome() != CompanyLifecycle.Outcome.DONE) {
			return statusChanged(redirectAttributes, locale);
		}
		redirectAttributes.addFlashAttribute("success",
				messageSource.getMessage(successKey, new Object[] { result.companyName() }, locale));
		return LIST_REDIRECT;
	}

	private String statusChanged(RedirectAttributes redirectAttributes, Locale locale) {
		redirectAttributes.addFlashAttribute("info",
				messageSource.getMessage("operator.companies.statusChanged", null, locale));
		return LIST_REDIRECT;
	}

	private String actionFailed(RedirectAttributes redirectAttributes, Locale locale) {
		redirectAttributes.addFlashAttribute("error",
				messageSource.getMessage("operator.companies.actionFailed", null, locale));
		return LIST_REDIRECT;
	}

}

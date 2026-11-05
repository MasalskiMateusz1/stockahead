package pl.regavio.stockahead.account;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import pl.regavio.stockahead.security.CompanyUser;

/**
 * The only page a user of a company that is not active (pending, rejected or
 * blocked) can reach: {@code AccountActivityFilter} redirects every other
 * route here. A user of an active company is sent to the dashboard. The
 * company's status is read through the principal's
 * {@code (accountId, companyId)}, never from a request parameter.
 */
@Controller
public class AwaitingApprovalController {

	private final AccountRepository accountRepository;

	public AwaitingApprovalController(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	@GetMapping("/awaiting-approval")
	@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")
	public String awaitingApproval(@AuthenticationPrincipal CompanyUser principal) {
		boolean active = accountRepository
			.findWithCompanyByIdAndCompanyId(principal.accountId(), principal.companyId())
			.map(account -> account.getCompany().getStatus() == CompanyStatus.ACTIVE)
			.orElse(false);
		return active ? "redirect:/" : "awaiting-approval";
	}

}

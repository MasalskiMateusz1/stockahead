package pl.regavio.stockahead.account;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The platform operator's only screen: a read-only list of every company
 * with its first-created manager's email, registration date and status. The
 * operator has no route to any company's warehouse data.
 */
@Controller
public class OperatorCompanyController {

	private final CompanyRepository companyRepository;

	public OperatorCompanyController(CompanyRepository companyRepository) {
		this.companyRepository = companyRepository;
	}

	@GetMapping("/operator/companies")
	@PreAuthorize("hasRole('OPERATOR')")
	public String list(Model model) {
		model.addAttribute("companies", companyRepository.findAllForOperatorList());
		return "operator-companies";
	}

}

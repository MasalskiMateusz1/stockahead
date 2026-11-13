package pl.regavio.stockahead.account;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import pl.regavio.stockahead.orders.OrderLineRepository;
import pl.regavio.stockahead.orders.OrderRepository;
import pl.regavio.stockahead.orders.OrderStatus;
import pl.regavio.stockahead.security.CompanyUser;

/**
 * Login page and the post-login landing: the operator is sent to the company
 * list, everyone else gets the warehouse dashboard. The dashboard opens with
 * count cards for the signed-in user's role, all scoped to the principal's
 * company: a manager gets the orders awaiting confirmation
 * ({@code pendingConfirmationCount}), the open orders
 * ({@code openOrderCount}) and the parts on the shopping list
 * ({@code shortagePartCount}); a technician gets the open orders assigned to
 * them ({@code myPickingCount}). Each count equals the rows of the list its
 * card links to. The signed-in account's email and role are shown by the
 * shared topbar fragment via Spring Security dialect attributes.
 */
@Controller
public class HomeController {

	private final OrderRepository orderRepository;

	private final OrderLineRepository orderLineRepository;

	HomeController(OrderRepository orderRepository, OrderLineRepository orderLineRepository) {
		this.orderRepository = orderRepository;
		this.orderLineRepository = orderLineRepository;
	}

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/")
	public String dashboard(Authentication authentication, @AuthenticationPrincipal CompanyUser principal,
			Model model) {
		if (hasAuthority(authentication, "ROLE_OPERATOR")) {
			return "redirect:/operator/companies";
		}
		Long companyId = principal.companyId();
		if (hasAuthority(authentication, "ROLE_MANAGER")) {
			model.addAttribute("pendingConfirmationCount", orderRepository
				.countByCompanyIdAndStatusAndCompletionReportedAtIsNotNull(companyId, OrderStatus.OPEN));
			model.addAttribute("openOrderCount", orderRepository
				.countByCompanyIdAndStatusAndCompletionReportedAtIsNull(companyId, OrderStatus.OPEN));
			model.addAttribute("shortagePartCount", orderLineRepository.countPartsWithShortage(companyId));
		}
		else {
			model.addAttribute("myPickingCount", orderRepository.countByCompanyIdAndStatusAndAssigneeId(companyId,
					OrderStatus.OPEN, principal.accountId()));
		}
		return "dashboard";
	}

	private static boolean hasAuthority(Authentication authentication, String authority) {
		return authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.anyMatch(authority::equals);
	}

}

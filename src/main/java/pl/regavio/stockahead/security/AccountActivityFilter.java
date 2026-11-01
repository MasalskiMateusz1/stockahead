package pl.regavio.stockahead.security;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pl.regavio.stockahead.account.AccountRepository;

public class AccountActivityFilter extends OncePerRequestFilter {

	private final AccountRepository accountRepository;

	public AccountActivityFilter(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)
				&& !isActiveAccount(authentication.getPrincipal())) {
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			response.sendRedirect(request.getContextPath() + "/login?deactivated");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isActiveAccount(Object principal) {
		if (!(principal instanceof CompanyUser user) || user.accountId() == null || user.companyId() == null) {
			return false;
		}
		return accountRepository.findByIdAndCompanyId(user.accountId(), user.companyId())
			.filter(account -> account.isActive())
			.isPresent();
	}

}

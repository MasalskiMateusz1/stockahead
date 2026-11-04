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
import pl.regavio.stockahead.account.Role;

/**
 * Ends the session of an authenticated account that no longer matches the
 * database: an account that is inactive or missing goes to
 * {@code /login?deactivated}; an active account whose role differs from the
 * authorities in its session (promoted or demoted since login) goes to
 * {@code /login?roleChanged}. Both checks use the one scoped account lookup
 * per request; the inactive/missing check wins when both apply.
 */
public class AccountActivityFilter extends OncePerRequestFilter {

	private static final String MANAGER_AUTHORITY = "ROLE_MANAGER";

	private enum AccountState {
		CURRENT, DEACTIVATED, ROLE_CHANGED
	}

	private final AccountRepository accountRepository;

	public AccountActivityFilter(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			AccountState state = accountState(authentication);
			if (state != AccountState.CURRENT) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				String reason = state == AccountState.ROLE_CHANGED ? "roleChanged" : "deactivated";
				response.sendRedirect(request.getContextPath() + "/login?" + reason);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private AccountState accountState(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof CompanyUser user) || user.accountId() == null
				|| user.companyId() == null) {
			return AccountState.DEACTIVATED;
		}
		boolean sessionIsManager = authentication.getAuthorities().stream()
			.anyMatch(authority -> MANAGER_AUTHORITY.equals(authority.getAuthority()));
		return accountRepository.findByIdAndCompanyId(user.accountId(), user.companyId())
			.map(account -> {
				if (!account.isActive()) {
					return AccountState.DEACTIVATED;
				}
				return (account.getRole() == Role.MANAGER) == sessionIsManager ? AccountState.CURRENT
						: AccountState.ROLE_CHANGED;
			})
			.orElse(AccountState.DEACTIVATED);
	}

}

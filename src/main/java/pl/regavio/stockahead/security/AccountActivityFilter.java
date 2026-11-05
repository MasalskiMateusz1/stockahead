package pl.regavio.stockahead.security;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

/**
 * Ends the session of an authenticated account that no longer matches the
 * database: an account that is inactive or missing goes to
 * {@code /login?deactivated}; an active account whose role's authorities
 * ({@link AccountUserDetailsService#authoritiesFor(Role)}) differ from the
 * {@code ROLE_*} authorities in its session (promoted or demoted since login) goes to
 * {@code /login?roleChanged}. Both checks use the one scoped account lookup
 * per request; the inactive/missing check wins when both apply.
 *
 * <p>A principal with a company is looked up by {@code (id, company_id)}. A
 * principal without one must be the operator: it is looked up among
 * company-less accounts and logged out as deactivated unless that account's
 * role is {@link Role#OPERATOR}.
 */
public class AccountActivityFilter extends OncePerRequestFilter {

	private static final String ROLE_PREFIX = "ROLE_";

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
		if (!(authentication.getPrincipal() instanceof CompanyUser user) || user.accountId() == null) {
			return AccountState.DEACTIVATED;
		}
		Optional<Account> account = user.companyId() == null
				? accountRepository.findByIdAndCompanyIdIsNull(user.accountId())
					.filter(operator -> operator.getRole() == Role.OPERATOR)
				: accountRepository.findByIdAndCompanyId(user.accountId(), user.companyId());
		Set<String> sessionRoles = roleNames(authentication.getAuthorities());
		return account
			.map(current -> {
				if (!current.isActive()) {
					return AccountState.DEACTIVATED;
				}
				return roleNames(AccountUserDetailsService.authoritiesFor(current.getRole())).equals(sessionRoles)
						? AccountState.CURRENT : AccountState.ROLE_CHANGED;
			})
			.orElse(AccountState.DEACTIVATED);
	}

	/**
	 * The {@code ROLE_*} authorities only: Spring Security adds others to an
	 * authenticated session (e.g. the {@code FACTOR_PASSWORD} factor
	 * authority), which say nothing about the account's role.
	 */
	private static Set<String> roleNames(Collection<? extends GrantedAuthority> authorities) {
		return authorities.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
			.collect(Collectors.toSet());
	}

}

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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.CompanyStatus;
import pl.regavio.stockahead.account.Role;

/**
 * Ends the session of an authenticated account that no longer matches the
 * database. First, an account whose email was released
 * ({@link Account#isEmailReleased()}, a rejected registration) goes to
 * {@code /login?error}, the same generic failure its login now gets. Then an
 * account that is inactive or missing goes to {@code /login?deactivated}; an
 * active account whose role's authorities
 * ({@link AccountUserDetailsService#authoritiesFor(Role)}) differ from the
 * {@code ROLE_*} authorities in its session (promoted or demoted since login) goes to
 * {@code /login?roleChanged}. All checks use the one scoped account lookup
 * per request. The released check wins over the inactive one (a released
 * account is always inactive), and the inactive/missing check wins over the
 * role-changed one.
 *
 * <p>Last, an active account with an unchanged role whose company's status
 * is not {@link CompanyStatus#ACTIVE} (pending, rejected or blocked) is
 * redirected to {@code /awaiting-approval} and the request goes no further.
 * The session is kept, so once the operator approves the company the same
 * session works on its next request. Requests matching the gate-exempt
 * matcher (the awaiting page itself, the error page and dispatch, static
 * resources) pass through, or the user would loop. This check runs only
 * after the released, deactivated and role-changed checks, so a deactivated
 * account of a pending company still goes to {@code /login?deactivated}.
 *
 * <p>A principal with a company is looked up by {@code (id, company_id)}. A
 * principal without one must be the operator: it is looked up among
 * company-less accounts and logged out as deactivated unless that account's
 * role is {@link Role#OPERATOR}. The operator's email is never released.
 */
public class AccountActivityFilter extends OncePerRequestFilter {

	private static final String ROLE_PREFIX = "ROLE_";

	private enum AccountState {
		CURRENT, RELEASED, DEACTIVATED, ROLE_CHANGED, COMPANY_NOT_ACTIVE
	}

	private final AccountRepository accountRepository;

	private final RequestMatcher gateExempt;

	/**
	 * @param gateExempt the requests the company-status gate lets through:
	 * they must stay reachable for a user of a company that is not active
	 */
	public AccountActivityFilter(AccountRepository accountRepository, RequestMatcher gateExempt) {
		this.accountRepository = accountRepository;
		this.gateExempt = gateExempt;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			AccountState state = accountState(authentication);
			if (state == AccountState.COMPANY_NOT_ACTIVE) {
				if (!gateExempt.matches(request)) {
					response.sendRedirect(request.getContextPath() + "/awaiting-approval");
					return;
				}
			}
			else if (state != AccountState.CURRENT) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				String reason = switch (state) {
					case RELEASED -> "error";
					case ROLE_CHANGED -> "roleChanged";
					default -> "deactivated";
				};
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
				: accountRepository.findWithCompanyByIdAndCompanyId(user.accountId(), user.companyId());
		Set<String> sessionRoles = roleNames(authentication.getAuthorities());
		return account
			.map(current -> {
				if (current.isEmailReleased()) {
					return AccountState.RELEASED;
				}
				if (!current.isActive()) {
					return AccountState.DEACTIVATED;
				}
				if (!roleNames(AccountUserDetailsService.authoritiesFor(current.getRole())).equals(sessionRoles)) {
					return AccountState.ROLE_CHANGED;
				}
				return current.getCompany() != null && current.getCompany().getStatus() != CompanyStatus.ACTIVE
						? AccountState.COMPANY_NOT_ACTIVE : AccountState.CURRENT;
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

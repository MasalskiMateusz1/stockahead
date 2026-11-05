package pl.regavio.stockahead.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.FilterChain;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.CompanyStatus;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A principal the filter cannot resolve to an account (not a
 * {@link CompanyUser}, or one without an account id) is logged out like a
 * deactivated account, without touching the repository or throwing. A
 * principal without a company must resolve to an active operator account:
 * an active operator passes, and a missing or inactive one, or a
 * company-less principal whose account is not the operator, is logged out
 * as deactivated.
 *
 * <p>A principal with a company is checked deactivated first, then
 * role-changed, then company status: a user of a company that is not
 * {@code ACTIVE} is redirected to {@code /awaiting-approval} with the session
 * kept, unless the request is gate-exempt; a user of an active company
 * passes.
 */
@ExtendWith(MockitoExtension.class)
class AccountActivityFilterTest {

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private FilterChain filterChain;

	/**
	 * Stands in for the matcher {@link SecurityConfig} builds (the awaiting
	 * page, the error page, static resources); the real one is exercised by
	 * {@code PendingCompanyAccessIntegrationTests}.
	 */
	private static final Set<String> EXEMPT_URIS = Set.of("/awaiting-approval", "/error", "/css/app.css");

	private static final RequestMatcher GATE_EXEMPT = request -> EXEMPT_URIS.contains(request.getRequestURI());

	private AccountActivityFilter filter() {
		return new AccountActivityFilter(accountRepository, GATE_EXEMPT);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void principalThatIsNotACompanyUserIsLoggedOut() throws Exception {
		assertLoggedOut(new User("someone@example.com", "x", List.of()));
		verifyNoInteractions(accountRepository);
	}

	@Test
	void companyUserWithoutAnAccountIdIsLoggedOut() throws Exception {
		assertLoggedOut(new CompanyUser("someone@example.com", "x", true, List.of(), null, 7L));
		verifyNoInteractions(accountRepository);
	}

	@Test
	void activeOperatorPasses() throws Exception {
		when(accountRepository.findByIdAndCompanyIdIsNull(3L)).thenReturn(Optional.of(account(Role.OPERATOR, true)));
		authenticate(operatorPrincipal());
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(session.isInvalid()).isFalse();
		assertThat(response.getRedirectedUrl()).isNull();
	}

	@Test
	void nonRoleAuthoritiesInTheSessionDoNotCountAsARoleChange() throws Exception {
		when(accountRepository.findByIdAndCompanyIdIsNull(3L)).thenReturn(Optional.of(account(Role.OPERATOR, true)));
		CompanyUser principal = operatorPrincipal();
		List<GrantedAuthority> sessionAuthorities = new ArrayList<>(principal.getAuthorities());
		sessionAuthorities.add(new SimpleGrantedAuthority("FACTOR_PASSWORD"));
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, sessionAuthorities));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(response.getRedirectedUrl()).isNull();
	}

	@Test
	void inactiveOperatorIsLoggedOut() throws Exception {
		when(accountRepository.findByIdAndCompanyIdIsNull(3L)).thenReturn(Optional.of(account(Role.OPERATOR, false)));

		assertLoggedOut(operatorPrincipal());
	}

	@Test
	void missingOperatorIsLoggedOut() throws Exception {
		when(accountRepository.findByIdAndCompanyIdIsNull(3L)).thenReturn(Optional.empty());

		assertLoggedOut(operatorPrincipal());
	}

	@Test
	void principalWithoutACompanyWhoseAccountIsNotTheOperatorIsLoggedOut() throws Exception {
		when(accountRepository.findByIdAndCompanyIdIsNull(3L)).thenReturn(Optional.of(account(Role.MANAGER, true)));

		assertLoggedOut(new CompanyUser("someone@example.com", "x", true,
				AccountUserDetailsService.authoritiesFor(Role.MANAGER), 3L, null));
	}

	@ParameterizedTest
	@EnumSource(value = CompanyStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
	void userOfCompanyThatIsNotActiveIsRedirectedToAwaitingApprovalWithSessionKept(CompanyStatus status)
			throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.MANAGER, true, status)));
		authenticate(companyPrincipal(Role.MANAGER));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/parts");
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		assertThat(response.getRedirectedUrl()).isEqualTo("/awaiting-approval");
		assertThat(session.isInvalid()).isFalse();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		verifyNoInteractions(filterChain);
	}

	@Test
	void redirectToAwaitingApprovalKeepsTheContextPath() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.TECHNICIAN, true, CompanyStatus.PENDING)));
		authenticate(companyPrincipal(Role.TECHNICIAN));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/");
		request.setContextPath("/app");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		assertThat(response.getRedirectedUrl()).isEqualTo("/app/awaiting-approval");
		verifyNoInteractions(filterChain);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/awaiting-approval", "/error", "/css/app.css" })
	void gateExemptRequestOfPendingCompanyUserPasses(String uri) throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.MANAGER, true, CompanyStatus.PENDING)));
		authenticate(companyPrincipal(Role.MANAGER));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(response.getRedirectedUrl()).isNull();
		assertThat(session.isInvalid()).isFalse();
	}

	@Test
	void deactivatedUserOfPendingCompanyIsLoggedOutAsDeactivated() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.MANAGER, false, CompanyStatus.PENDING)));

		assertLoggedOut(companyPrincipal(Role.MANAGER), "/login?deactivated");
	}

	@Test
	void deactivatedUserOfPendingCompanyIsLoggedOutEvenOnGateExemptRequest() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.MANAGER, false, CompanyStatus.PENDING)));
		authenticate(companyPrincipal(Role.MANAGER));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/awaiting-approval");
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		assertThat(session.isInvalid()).isTrue();
		assertThat(response.getRedirectedUrl()).isEqualTo("/login?deactivated");
		verifyNoInteractions(filterChain);
	}

	@Test
	void roleChangedUserOfPendingCompanyIsLoggedOutAsRoleChanged() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.TECHNICIAN, true, CompanyStatus.PENDING)));

		assertLoggedOut(companyPrincipal(Role.MANAGER), "/login?roleChanged");
	}

	@Test
	void userOfActiveCompanyPasses() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L))
			.thenReturn(Optional.of(companyAccount(Role.MANAGER, true, CompanyStatus.ACTIVE)));
		authenticate(companyPrincipal(Role.MANAGER));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/parts");
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(response.getRedirectedUrl()).isNull();
		assertThat(session.isInvalid()).isFalse();
	}

	@Test
	void missingCompanyAccountIsLoggedOut() throws Exception {
		when(accountRepository.findWithCompanyByIdAndCompanyId(5L, 7L)).thenReturn(Optional.empty());

		assertLoggedOut(companyPrincipal(Role.MANAGER), "/login?deactivated");
	}

	private static CompanyUser companyPrincipal(Role role) {
		return new CompanyUser("user@example.com", "x", true, AccountUserDetailsService.authoritiesFor(role), 5L, 7L);
	}

	private static Account companyAccount(Role role, boolean active, CompanyStatus status) {
		Company company = new Company();
		company.setId(7L);
		company.setName("Example Co");
		company.setStatus(status);
		company.setCreatedAt(Instant.now());
		Account account = account(role, active);
		account.setId(5L);
		account.setEmail("user@example.com");
		account.setCompany(company);
		return account;
	}

	private static CompanyUser operatorPrincipal() {
		return new CompanyUser("operator@example.com", "x", true,
				AccountUserDetailsService.authoritiesFor(Role.OPERATOR), 3L, null);
	}

	private static Account account(Role role, boolean active) {
		Account account = new Account();
		account.setId(3L);
		account.setEmail("operator@example.com");
		account.setPasswordHash("hashed-password");
		account.setRole(role);
		account.setActive(active);
		account.setCreatedAt(Instant.now());
		return account;
	}

	private static void authenticate(CompanyUser principal) {
		SecurityContextHolder.getContext()
			.setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
	}

	private void assertLoggedOut(Object principal) throws Exception {
		assertLoggedOut(principal, "/login?deactivated");
	}

	private void assertLoggedOut(Object principal, String expectedRedirect) throws Exception {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
					principal instanceof User user ? user.getAuthorities() : List.of()));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request, response, filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(session.isInvalid()).isTrue();
		assertThat(response.getRedirectedUrl()).isEqualTo(expectedRedirect);
		verifyNoInteractions(filterChain);
	}

}

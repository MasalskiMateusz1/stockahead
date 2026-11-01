package pl.regavio.stockahead.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import jakarta.servlet.FilterChain;
import pl.regavio.stockahead.account.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A principal the filter cannot resolve to an account (not a
 * {@link CompanyUser}, or one without an account id) is logged out like a
 * deactivated account, without touching the repository or throwing.
 */
@ExtendWith(MockitoExtension.class)
class AccountActivityFilterTest {

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private FilterChain filterChain;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void principalThatIsNotACompanyUserIsLoggedOut() throws Exception {
		assertLoggedOut(new User("someone@example.com", "x", List.of()));
	}

	@Test
	void companyUserWithoutAnAccountIdIsLoggedOut() throws Exception {
		assertLoggedOut(new CompanyUser("someone@example.com", "x", true, List.of(), null, 7L));
	}

	private void assertLoggedOut(Object principal) throws Exception {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();

		new AccountActivityFilter(accountRepository).doFilter(request, response, filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(session.isInvalid()).isTrue();
		assertThat(response.getRedirectedUrl()).isEqualTo("/login?deactivated");
		verifyNoInteractions(filterChain, accountRepository);
	}

}

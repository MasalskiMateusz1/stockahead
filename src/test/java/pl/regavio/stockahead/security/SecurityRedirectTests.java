package pl.regavio.stockahead.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.HomeController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the security filter chain redirects an unauthenticated request to
 * /login. Deliberately avoids Testcontainers/Postgres: {@code @WebMvcTest}
 * excludes DataSource auto-configuration; the JPA-backed user details service
 * and account repository are mocked. An unauthenticated request reaches neither.
 */
@WebMvcTest(controllers = HomeController.class)
@Import(SecurityConfig.class)
class SecurityRedirectTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountUserDetailsService accountUserDetailsService;

	@MockitoBean
	private AccountRepository accountRepository;

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
	}

}

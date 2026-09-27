package pl.regavio.stockahead.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.regavio.stockahead.account.HomeController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the security filter chain redirects an unauthenticated request to
 * /login. Deliberately avoids Testcontainers/Postgres: {@code @WebMvcTest}
 * excludes DataSource auto-configuration, and {@code AccountUserDetailsService}
 * is mocked so its real (JPA-backed) implementation is never invoked — an
 * unauthenticated request never reaches the UserDetailsService anyway.
 */
@WebMvcTest(controllers = HomeController.class)
@Import(SecurityConfig.class)
class SecurityRedirectTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountUserDetailsService accountUserDetailsService;

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/login")));
	}

}

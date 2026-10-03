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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks in anonymous access to the shared stylesheet and theme script, and
 * their presence on the login page, which renders before any session exists.
 * Same no-Postgres slice as {@link SecurityRedirectTests}: HomeController
 * serves /login; the JPA-backed beans are mocked and never reached.
 */
@WebMvcTest(controllers = HomeController.class)
@Import(SecurityConfig.class)
class StaticResourceAccessTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountUserDetailsService accountUserDetailsService;

	@MockitoBean
	private AccountRepository accountRepository;

	@Test
	void anonymousCanLoadStylesheet() throws Exception {
		mockMvc.perform(get("/css/app.css"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/css"));
	}

	@Test
	void anonymousCanLoadThemeScript() throws Exception {
		mockMvc.perform(get("/js/theme.js"))
			.andExpect(status().isOk());
	}

	@Test
	void loginPageReferencesStylesheetAndThemeScript() throws Exception {
		mockMvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("/css/app")))
			.andExpect(content().string(containsString("/js/theme")));
	}

}

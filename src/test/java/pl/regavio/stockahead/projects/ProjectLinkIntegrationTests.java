package pl.regavio.stockahead.projects;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.TestcontainersConfiguration;
import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers documentation links on the project detail page (add with and
 * without a label, remove), the server-side URL whitelist that keeps
 * {@code javascript:} and other non-http(s) URLs out of {@code th:href}, the
 * error paths that re-render the detail page, and the role split on every
 * link route, against a real Postgres via Testcontainers. Deliberately not
 * {@code @Transactional}: each MockMvc request commits its own transaction,
 * as in production.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectLinkIntegrationTests {

	private static final String MANAGER_EMAIL = "project-link-manager@example.com";

	private static final String TECHNICIAN_EMAIL = "project-link-technician@example.com";

	private static final String PASSWORD = "correct-password";

	private static final String URL_REQUIRED_ERROR = "Adres linku jest wymagany.";

	private static final String URL_TOO_LONG_ERROR = "Adres linku może mieć maksymalnie 2048 znaków.";

	private static final String URL_INVALID_ERROR =
			"Adres linku musi zaczynać się od http:// lub https:// i zawierać nazwę hosta.";

	private static final String LABEL_TOO_LONG_ERROR = "Opis linku może mieć maksymalnie 255 znaków.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private Long projectId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		cleanUp();
		projectId = seedProject("Link Board", true);
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		jdbcTemplate.update("DELETE FROM project_links");
		jdbcTemplate.update("DELETE FROM bom_lines");
		jdbcTemplate.update("DELETE FROM projects");
		jdbcTemplate.update("DELETE FROM part_locations");
		jdbcTemplate.update("DELETE FROM parts");
		accountRepository.findByEmail(MANAGER_EMAIL).ifPresent(accountRepository::delete);
		accountRepository.findByEmail(TECHNICIAN_EMAIL).ifPresent(accountRepository::delete);
	}

	// ---- fixtures -----------------------------------------------------

	private void seedAccount(String email, Role role) {
		transactionTemplate.executeWithoutResult(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(PASSWORD));
			account.setRole(role);
			account.setActive(true);
			account.setCreatedAt(Instant.now());
			accountRepository.save(account);
		});
	}

	private MockHttpSession loginAs(String email) throws Exception {
		return (MockHttpSession) mockMvc.perform(formLogin().user(email).password(PASSWORD))
			.andExpect(status().is3xxRedirection())
			.andReturn().getRequest().getSession();
	}

	private MockHttpSession managerSession() throws Exception {
		seedAccount(MANAGER_EMAIL, Role.MANAGER);
		return loginAs(MANAGER_EMAIL);
	}

	private MockHttpSession technicianSession() throws Exception {
		seedAccount(TECHNICIAN_EMAIL, Role.TECHNICIAN);
		return loginAs(TECHNICIAN_EMAIL);
	}

	private Long seedProject(String name, boolean active) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO projects (name, active) VALUES (?, ?) RETURNING id", Long.class, name, active));
	}

	private Long seedLink(Long project, String url, String label) {
		return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"INSERT INTO project_links (project_id, url, label) VALUES (?, ?, ?) RETURNING id", Long.class,
				project, url, label));
	}

	private int linkCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM project_links", Integer.class);
	}

	private List<Long> linkIds() {
		return jdbcTemplate.queryForList("SELECT id FROM project_links ORDER BY id", Long.class);
	}

	/**
	 * Returns the opening {@code <a>} tag whose {@code href} is exactly
	 * {@code href}, plus its text, from the rendered page.
	 */
	private static Matcher anchor(String html, String href) {
		Matcher anchor = Pattern.compile("(<a[^>]*\\shref=\"" + Pattern.quote(href) + "\"[^>]*>)([^<]*)</a>")
			.matcher(html);
		assertThat(anchor.find()).as("anchor to %s present", href).isTrue();
		return anchor;
	}

	// ---- add ----------------------------------------------------------

	@Test
	void managerAddsLabelledLinkAndDetailPageRendersSafeAnchor() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", "  https://docs.example.com/board.pdf  ")
			.param("label", "  Schemat płytki  "))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(jdbcTemplate.queryForMap("SELECT project_id, url, label FROM project_links"))
			.containsEntry("project_id", projectId)
			.containsEntry("url", "https://docs.example.com/board.pdf")
			.containsEntry("label", "Schemat płytki");

		String html = mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Brak linków do dokumentacji."))))
			.andReturn().getResponse().getContentAsString();

		Matcher anchor = anchor(html, "https://docs.example.com/board.pdf");
		assertThat(anchor.group(1)).contains("target=\"_blank\"").contains("rel=\"noopener noreferrer\"");
		assertThat(anchor.group(2)).isEqualTo("Schemat płytki");
	}

	@Test
	void linkWithoutLabelStoresNullAndRendersUrlAsText() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", "HTTP://wiki.example.com/page")
			.param("label", "   "))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(jdbcTemplate.queryForObject("SELECT label FROM project_links", String.class)).isNull();

		String html = mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		Matcher anchor = anchor(html, "HTTP://wiki.example.com/page");
		assertThat(anchor.group(1)).contains("target=\"_blank\"").contains("rel=\"noopener noreferrer\"");
		assertThat(anchor.group(2)).isEqualTo("HTTP://wiki.example.com/page");
	}

	@Test
	void unsafeOrMalformedUrlsAreRejectedWithErrorAndNoRow() throws Exception {
		MockHttpSession session = managerSession();

		for (String[] attempt : new String[][] { { "javascript:alert(1)", URL_INVALID_ERROR },
				{ "ftp://example.com/x", URL_INVALID_ERROR }, { "http://", URL_INVALID_ERROR },
				{ "https:///no-host", URL_INVALID_ERROR }, { "not a url", URL_INVALID_ERROR },
				{ "   ", URL_REQUIRED_ERROR } }) {
			mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
				.param("url", attempt[0])
				.param("label", "Dokumentacja"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(attempt[1])))
				.andExpect(content().string(containsString("Link Board")))
				.andExpect(content().string(containsString("value=\"Dokumentacja\"")))
				.andExpect(content().string(not(containsString("href=\"javascript:"))));
		}

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("label", "Bez adresu"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(URL_REQUIRED_ERROR)));

		assertThat(linkCount()).isZero();
	}

	@Test
	void submittedUrlIsEchoedBackOnError() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", "ftp://example.com/x")
			.param("label", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"ftp://example.com/x\"")));

		assertThat(linkCount()).isZero();
	}

	@Test
	void urlOver2048CharactersIsRejected() throws Exception {
		MockHttpSession session = managerSession();
		String prefix = "https://example.com/";
		String exactlyMax = prefix + "a".repeat(2048 - prefix.length());

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", exactlyMax + "a"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(URL_TOO_LONG_ERROR)));

		assertThat(linkCount()).isZero();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", exactlyMax))
			.andExpect(status().is3xxRedirection());

		assertThat(linkCount()).isEqualTo(1);
	}

	@Test
	void labelOver255CharactersIsRejected() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", "https://example.com/doc")
			.param("label", "x".repeat(256)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(LABEL_TOO_LONG_ERROR)));

		assertThat(linkCount()).isZero();
	}

	@Test
	void addingToUnknownProjectIs404() throws Exception {
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/999999/links").session(session).with(csrf())
			.param("url", "https://example.com/doc"))
			.andExpect(status().isNotFound());

		assertThat(linkCount()).isZero();
	}

	// ---- remove ---------------------------------------------------------

	@Test
	void managerRemovesLink() throws Exception {
		Long removedLink = seedLink(projectId, "https://example.com/old", "Stara wersja");
		Long keptLink = seedLink(projectId, "https://example.com/new", null);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links/" + removedLink + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/projects/" + projectId));

		assertThat(linkIds()).containsExactly(keptLink);
	}

	@Test
	void linkOfAnotherProjectIs404AndChangesNothing() throws Exception {
		Long otherProject = seedProject("Other Board", true);
		Long foreignLink = seedLink(otherProject, "https://example.com/other", "Inny projekt");
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links/" + foreignLink + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(linkIds()).containsExactly(foreignLink);
	}

	@Test
	void unknownLinkOrProjectIs404() throws Exception {
		Long linkId = seedLink(projectId, "https://example.com/doc", null);
		MockHttpSession session = managerSession();

		mockMvc.perform(post("/projects/" + projectId + "/links/999999/delete").session(session).with(csrf()))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/projects/999999/links/" + linkId + "/delete").session(session).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(linkIds()).containsExactly(linkId);
	}

	// ---- role gating ----------------------------------------------------

	@Test
	void technicianSeesLinksWithoutEditForms() throws Exception {
		seedLink(projectId, "https://example.com/manual", "Instrukcja montażu");
		MockHttpSession session = technicianSession();

		String html = mockMvc.perform(get("/projects/" + projectId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("/links"))))
			.andExpect(content().string(not(containsString("name=\"url\""))))
			.andReturn().getResponse().getContentAsString();

		assertThat(anchor(html, "https://example.com/manual").group(2)).isEqualTo("Instrukcja montażu");
	}

	@Test
	void technicianGets403OnEveryLinkRoute() throws Exception {
		Long linkId = seedLink(projectId, "https://example.com/doc", "Dokumentacja");
		MockHttpSession session = technicianSession();

		mockMvc.perform(post("/projects/" + projectId + "/links").session(session).with(csrf())
			.param("url", "https://example.com/new"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/projects/" + projectId + "/links/" + linkId + "/delete").session(session)
			.with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(linkIds()).containsExactly(linkId);
	}

}

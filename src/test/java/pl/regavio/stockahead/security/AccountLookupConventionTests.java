package pl.regavio.stockahead.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the account-lookup convention: only login resolves an account by
 * email. Everything else takes the current account id from
 * {@link CompanyUser#accountId()} and loads it with
 * {@code AccountRepository.findByIdAndCompanyId(accountId, companyId)}.
 * {@code AccountRepository.findByEmail} is test-only: since V21 a released
 * account can share its email with a live one, so that lookup may match two
 * rows and must never be called from production code.
 */
class AccountLookupConventionTests {

	private static final Path MAIN_SOURCES = Path.of("src/main/java");

	private static final Path PACKAGE_ROOT = MAIN_SOURCES.resolve("pl/regavio/stockahead");

	private static final List<Path> ALLOWED = List.of(PACKAGE_ROOT.resolve("account/AccountRepository.java"),
			PACKAGE_ROOT.resolve("security/AccountUserDetailsService.java"));

	@Test
	void onlyLoginLooksUpAccountsByEmail() throws IOException {
		List<Path> callers;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			callers = files.filter(path -> path.getFileName().toString().endsWith(".java"))
				.filter(AccountLookupConventionTests::callsFindByCanonicalEmail)
				.sorted()
				.toList();
		}

		assertThat(callers).as("files in %s calling findByCanonicalEmail (only login may)",
				MAIN_SOURCES.toAbsolutePath())
			.containsExactlyInAnyOrderElementsOf(ALLOWED);
	}

	@Test
	void productionCodeNeverCallsTheTestOnlyFindByEmail() throws IOException {
		List<Path> callers;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			callers = files.filter(path -> path.getFileName().toString().endsWith(".java"))
				.filter(path -> contains(path, "findByEmail("))
				.sorted()
				.toList();
		}

		assertThat(callers).as("files in %s calling the test-only findByEmail", MAIN_SOURCES.toAbsolutePath())
			.containsExactly(PACKAGE_ROOT.resolve("account/AccountRepository.java"));
	}

	private static boolean callsFindByCanonicalEmail(Path file) {
		return contains(file, "findByCanonicalEmail");
	}

	private static boolean contains(Path file, String text) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8).contains(text);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + file, ex);
		}
	}

}

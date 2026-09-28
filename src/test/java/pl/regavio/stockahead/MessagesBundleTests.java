package pl.regavio.stockahead;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every key in {@code messages.properties} resolves to exactly
 * the Polish text users see today. This is the only test that would catch a
 * typo'd key or a missing entry in the bundle.
 */
class MessagesBundleTests {

	private final ResourceBundleMessageSource messageSource = buildMessageSource();

	private static ResourceBundleMessageSource buildMessageSource() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		return source;
	}

	private static final Locale PL = Locale.forLanguageTag("pl");

	@Test
	void setupErrorInvalidToken() {
		assertThat(messageSource.getMessage("setup.error.invalidToken", null, PL))
			.isEqualTo("Nieprawidłowy token konfiguracyjny.");
	}

	@Test
	void setupErrorPasswordMismatch() {
		assertThat(messageSource.getMessage("setup.error.passwordMismatch", null, PL))
			.isEqualTo("Hasła nie są identyczne.");
	}

	@Test
	void setupErrorAccountCreationFailed() {
		assertThat(messageSource.getMessage("setup.error.accountCreationFailed", null, PL))
			.isEqualTo("Nie udało się założyć konta. Spróbuj ponownie.");
	}

	@Test
	void partsErrorNameRequired() {
		assertThat(messageSource.getMessage("parts.error.nameRequired", null, PL))
			.isEqualTo("Nazwa jest wymagana.");
	}

	@Test
	void partsErrorNameTooLong() {
		assertThat(messageSource.getMessage("parts.error.nameTooLong", new Object[] { 255 }, PL))
			.isEqualTo("Nazwa może mieć maksymalnie 255 znaków.");
	}

	@Test
	void partsErrorDuplicateName() {
		assertThat(messageSource.getMessage("parts.error.duplicateName", null, PL))
			.isEqualTo("Część o tej nazwie już istnieje.");
	}

	@Test
	void partsErrorQuantityNotInteger() {
		assertThat(messageSource.getMessage("parts.error.quantityNotInteger", null, PL))
			.isEqualTo("Stan magazynowy musi być liczbą całkowitą.");
	}

	@Test
	void partsErrorQuantityNegative() {
		assertThat(messageSource.getMessage("parts.error.quantityNegative", null, PL))
			.isEqualTo("Stan magazynowy nie może być ujemny.");
	}

	@Test
	void partsErrorLocationsRequired() {
		assertThat(messageSource.getMessage("parts.error.locationsRequired", null, PL))
			.isEqualTo("Podaj co najmniej jedną lokalizację.");
	}

	@Test
	void partsErrorLocationTooLong() {
		assertThat(messageSource.getMessage("parts.error.locationTooLong", new Object[] { 255 }, PL))
			.isEqualTo("Lokalizacja może mieć maksymalnie 255 znaków.");
	}

	@Test
	void partsErrorLocationDuplicate() {
		assertThat(messageSource.getMessage("parts.error.locationDuplicate", new Object[] { "Regał A1" }, PL))
			.isEqualTo("Lokalizacja „Regał A1” została podana więcej niż raz.");
	}

	@Test
	void projectsErrorNameRequired() {
		assertThat(messageSource.getMessage("projects.error.nameRequired", null, PL))
			.isEqualTo("Nazwa projektu jest wymagana.");
	}

	@Test
	void projectsErrorNameTooLong() {
		assertThat(messageSource.getMessage("projects.error.nameTooLong", new Object[] { 255 }, PL))
			.isEqualTo("Nazwa projektu może mieć maksymalnie 255 znaków.");
	}

	@Test
	void projectsErrorDuplicateName() {
		assertThat(messageSource.getMessage("projects.error.duplicateName", null, PL))
			.isEqualTo("Projekt o tej nazwie już istnieje.");
	}

	@Test
	void loginErrorBadCredentials() {
		assertThat(messageSource.getMessage("login.error.badCredentials", null, PL))
			.isEqualTo("Nieprawidłowy e-mail lub hasło.");
	}

	@Test
	void loginStatusLoggedOut() {
		assertThat(messageSource.getMessage("login.status.loggedOut", null, PL)).isEqualTo("Wylogowano.");
	}

	@Test
	void loginStatusManagerCreated() {
		assertThat(messageSource.getMessage("login.status.managerCreated", null, PL))
			.isEqualTo("Konto kierownika zostało założone. Zaloguj się.");
	}

}

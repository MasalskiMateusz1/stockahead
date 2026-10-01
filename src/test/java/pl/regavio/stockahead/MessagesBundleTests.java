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

	@Test
	void projectsBomErrorQuantityNotInteger() {
		assertThat(messageSource.getMessage("projects.bom.error.quantityNotInteger", null, PL))
			.isEqualTo("Ilość na sztukę musi być liczbą całkowitą.");
	}

	@Test
	void projectsBomErrorQuantityNotPositive() {
		assertThat(messageSource.getMessage("projects.bom.error.quantityNotPositive", null, PL))
			.isEqualTo("Ilość na sztukę musi wynosić co najmniej 1.");
	}

	@Test
	void projectsBomErrorPartUnavailable() {
		assertThat(messageSource.getMessage("projects.bom.error.partUnavailable", null, PL))
			.isEqualTo("Część nieaktywna lub nie istnieje.");
	}

	@Test
	void projectsBomErrorDuplicatePart() {
		assertThat(messageSource.getMessage("projects.bom.error.duplicatePart", null, PL))
			.isEqualTo("Ta część jest już w liście materiałowej. Zmień ilość w istniejącej pozycji.");
	}

	@Test
	void projectsBomErrorSaveFailed() {
		assertThat(messageSource.getMessage("projects.bom.error.saveFailed", null, PL))
			.isEqualTo("Nie udało się zapisać pozycji. Spróbuj ponownie.");
	}

	@Test
	void projectsLinksErrorUrlRequired() {
		assertThat(messageSource.getMessage("projects.links.error.urlRequired", null, PL))
			.isEqualTo("Adres linku jest wymagany.");
	}

	@Test
	void projectsLinksErrorUrlTooLong() {
		assertThat(messageSource.getMessage("projects.links.error.urlTooLong", new Object[] { 2048 }, PL))
			.isEqualTo("Adres linku może mieć maksymalnie 2048 znaków.");
	}

	@Test
	void projectsLinksErrorUrlInvalid() {
		assertThat(messageSource.getMessage("projects.links.error.urlInvalid", null, PL))
			.isEqualTo("Adres linku musi zaczynać się od http:// lub https://, zawierać poprawną nazwę hosta (bez podkreśleń i polskich znaków) i nie może zawierać spacji.");
	}

	@Test
	void projectsLinksErrorLabelTooLong() {
		assertThat(messageSource.getMessage("projects.links.error.labelTooLong", new Object[] { 255 }, PL))
			.isEqualTo("Opis linku może mieć maksymalnie 255 znaków.");
	}

	@Test
	void projectsLinksErrorSaveFailed() {
		assertThat(messageSource.getMessage("projects.links.error.saveFailed", null, PL))
			.isEqualTo("Nie udało się zapisać linku. Sprawdź adres i spróbuj ponownie.");
	}

	@Test
	void pickingErrorCompletionReported() {
		assertThat(messageSource.getMessage("picking.error.completionReported", null, PL))
			.isEqualTo("Zlecenie zostało zgłoszone jako zakończone — pobieranie jest wstrzymane.");
	}

	@Test
	void pickingErrorReportNotTaken() {
		assertThat(messageSource.getMessage("picking.error.reportNotTaken", null, PL))
			.isEqualTo("Nie można zgłosić zakończenia zlecenia, które nie zostało podjęte.");
	}

	@Test
	void pickingErrorAlreadyReported() {
		assertThat(messageSource.getMessage("picking.error.alreadyReported", null, PL))
			.isEqualTo("Zlecenie zostało już zgłoszone jako zakończone.");
	}

	@Test
	void pickingErrorReportFailed() {
		assertThat(messageSource.getMessage("picking.error.reportFailed", null, PL))
			.isEqualTo("Nie udało się zgłosić zakończenia. Spróbuj ponownie.");
	}

	@Test
	void ordersErrorNotReported() {
		assertThat(messageSource.getMessage("orders.error.notReported", null, PL))
			.isEqualTo("Zlecenie nie oczekuje na potwierdzenie zakończenia.");
	}

	@Test
	void ordersErrorCompletionFailed() {
		assertThat(messageSource.getMessage("orders.error.completionFailed", null, PL))
			.isEqualTo("Nie udało się zapisać decyzji o zakończeniu zlecenia. Spróbuj ponownie.");
	}

	@Test
	void purchasingCsvHeaderPartName() {
		assertThat(messageSource.getMessage("purchasing.csv.header.partName", null, PL)).isEqualTo("Część");
	}

	@Test
	void purchasingCsvHeaderOrder() {
		assertThat(messageSource.getMessage("purchasing.csv.header.order", null, PL)).isEqualTo("Zlecenie");
	}

	@Test
	void purchasingCsvHeaderRequiredDate() {
		assertThat(messageSource.getMessage("purchasing.csv.header.requiredDate", null, PL)).isEqualTo("Termin");
	}

	@Test
	void purchasingCsvHeaderMissingQuantity() {
		assertThat(messageSource.getMessage("purchasing.csv.header.missingQuantity", null, PL))
			.isEqualTo("Brakująca ilość");
	}

}

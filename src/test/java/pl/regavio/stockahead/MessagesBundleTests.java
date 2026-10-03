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

	@Test
	void correctionsErrorNewQuantityNotInteger() {
		assertThat(messageSource.getMessage("corrections.error.newQuantityNotInteger", null, PL))
			.isEqualTo("Nowy stan musi być liczbą całkowitą.");
	}

	@Test
	void correctionsErrorNewQuantityNegative() {
		assertThat(messageSource.getMessage("corrections.error.newQuantityNegative", null, PL))
			.isEqualTo("Nowy stan nie może być ujemny.");
	}

	@Test
	void correctionsErrorNewQuantityTooLarge() {
		assertThat(messageSource.getMessage("corrections.error.newQuantityTooLarge", new Object[] { Integer.MAX_VALUE }, PL))
			.isEqualTo("Nowy stan nie może przekraczać 2147483647.");
	}

	@Test
	void correctionsErrorDeltaNotInteger() {
		assertThat(messageSource.getMessage("corrections.error.deltaNotInteger", null, PL))
			.isEqualTo("Zmiana musi być liczbą całkowitą.");
	}

	@Test
	void correctionsErrorDeltaZero() {
		assertThat(messageSource.getMessage("corrections.error.deltaZero", null, PL))
			.isEqualTo("Zmiana nie może wynosić 0.");
	}

	@Test
	void correctionsErrorDeltaTooLarge() {
		assertThat(messageSource.getMessage("corrections.error.deltaTooLarge", new Object[] { 1_000_000 }, PL))
			.isEqualTo("Zmiana nie może przekraczać 1000000 szt. w żadną stronę.");
	}

	@Test
	void correctionsErrorReasonRequired() {
		assertThat(messageSource.getMessage("corrections.error.reasonRequired", null, PL))
			.isEqualTo("Podaj powód korekty.");
	}

	@Test
	void correctionsErrorReasonTooLong() {
		assertThat(messageSource.getMessage("corrections.error.reasonTooLong", new Object[] { 500 }, PL))
			.isEqualTo("Powód może mieć maksymalnie 500 znaków.");
	}

	@Test
	void correctionsErrorStale() {
		assertThat(messageSource.getMessage("corrections.error.stale", new Object[] { 1234 }, PL))
			.isEqualTo("Stan części zmienił się w międzyczasie i wynosi teraz 1234. Sprawdź go i zatwierdź ponownie.");
	}

	@Test
	void correctionsErrorNoChange() {
		assertThat(messageSource.getMessage("corrections.error.noChange", new Object[] { 1234 }, PL))
			.isEqualTo("Nowy stan jest równy obecnemu (1234). Nie ma czego korygować.");
	}

	@Test
	void correctionsErrorStockOverflow() {
		assertThat(messageSource.getMessage("corrections.error.stockOverflow", null, PL))
			.isEqualTo("Stan części przekroczyłby dopuszczalny zakres.");
	}

	@Test
	void correctionsErrorBelowZero() {
		assertThat(messageSource.getMessage("corrections.error.belowZero", new Object[] { 1234 }, PL))
			.isEqualTo("Stan nie może spaść poniżej zera: obecny stan to 1234.");
	}

	@Test
	void correctionsErrorSaveFailed() {
		assertThat(messageSource.getMessage("corrections.error.saveFailed", null, PL))
			.isEqualTo("Nie udało się zapisać korekty. Spróbuj ponownie.");
	}

	@Test
	void correctionsApplied() {
		assertThat(messageSource.getMessage("corrections.applied", new Object[] { "Rezystor 10k", 1200, 5 }, PL))
			.isEqualTo("Skorygowano stan części „Rezystor 10k”: 1200 → 5.");
	}

	@Test
	void partsImportErrorFileRequired() {
		assertThat(messageSource.getMessage("partsImport.error.fileRequired", null, PL))
			.isEqualTo("Wybierz plik CSV do zaimportowania.");
	}

	@Test
	void partsImportErrorFileTooLarge() {
		assertThat(messageSource.getMessage("partsImport.error.fileTooLarge", null, PL))
			.isEqualTo("Plik jest za duży. Maksymalny rozmiar to 1 MB.");
	}

	@Test
	void partsImportErrorReadFailed() {
		assertThat(messageSource.getMessage("partsImport.error.readFailed", null, PL))
			.isEqualTo("Nie udało się odczytać pliku. Spróbuj ponownie.");
	}

	@Test
	void partsImportErrorEmptyFile() {
		assertThat(messageSource.getMessage("partsImport.error.emptyFile", null, PL))
			.isEqualTo("Plik jest pusty.");
	}

	@Test
	void partsImportErrorMissingColumns() {
		assertThat(messageSource.getMessage("partsImport.error.missingColumns", new Object[] { "Ilość, Lokalizacja" }, PL))
			.isEqualTo("Brak wymaganych kolumn w nagłówku: Ilość, Lokalizacja. Pierwszy wiersz pliku musi zawierać kolumny Nazwa, Ilość i Lokalizacja (albo po angielsku Name, Quantity i Location).");
	}

	@Test
	void partsImportColumnLabels() {
		assertThat(messageSource.getMessage("partsImport.column.name", null, PL)).isEqualTo("Nazwa");
		assertThat(messageSource.getMessage("partsImport.column.quantity", null, PL)).isEqualTo("Ilość");
		assertThat(messageSource.getMessage("partsImport.column.location", null, PL)).isEqualTo("Lokalizacja");
	}

	@Test
	void partsImportErrorUnterminatedQuote() {
		assertThat(messageSource.getMessage("partsImport.error.unterminatedQuote", new Object[] { 1234 }, PL))
			.isEqualTo("Wiersz 1234: cudzysłów otwarty w tym wierszu nie został zamknięty.");
	}

	@Test
	void partsImportErrorTooManyRows() {
		assertThat(messageSource.getMessage("partsImport.error.tooManyRows", new Object[] { 5000 }, PL))
			.isEqualTo("Plik może mieć maksymalnie 5000 wierszy z danymi.");
	}

	@Test
	void partsImportErrorNoDataRows() {
		assertThat(messageSource.getMessage("partsImport.error.noDataRows", null, PL))
			.isEqualTo("Plik nie zawiera żadnych wierszy z danymi.");
	}

	@Test
	void partsImportErrorNameRequired() {
		assertThat(messageSource.getMessage("partsImport.error.nameRequired", new Object[] { 1234 }, PL))
			.isEqualTo("Wiersz 1234: nazwa jest wymagana.");
	}

	@Test
	void partsImportErrorNameTooLong() {
		assertThat(messageSource.getMessage("partsImport.error.nameTooLong", new Object[] { 1234, 255 }, PL))
			.isEqualTo("Wiersz 1234: nazwa może mieć maksymalnie 255 znaków.");
	}

	@Test
	void partsImportErrorQuantityRequired() {
		assertThat(messageSource.getMessage("partsImport.error.quantityRequired", new Object[] { 1234 }, PL))
			.isEqualTo("Wiersz 1234: ilość jest wymagana.");
	}

	@Test
	void partsImportErrorQuantityNotInteger() {
		assertThat(messageSource.getMessage("partsImport.error.quantityNotInteger", new Object[] { 1234 }, PL))
			.isEqualTo("Wiersz 1234: ilość musi być liczbą całkowitą.");
	}

	@Test
	void partsImportErrorQuantityNegative() {
		assertThat(messageSource.getMessage("partsImport.error.quantityNegative", new Object[] { 1234 }, PL))
			.isEqualTo("Wiersz 1234: ilość nie może być ujemna.");
	}

	@Test
	void partsImportErrorQuantityTooLarge() {
		assertThat(messageSource.getMessage("partsImport.error.quantityTooLarge", new Object[] { 1234, 1_000_000 }, PL))
			.isEqualTo("Wiersz 1234: ilość nie może przekraczać 1000000.");
	}

	@Test
	void partsImportErrorLocationTooLong() {
		assertThat(messageSource.getMessage("partsImport.error.locationTooLong", new Object[] { 1234, 255 }, PL))
			.isEqualTo("Wiersz 1234: lokalizacja może mieć maksymalnie 255 znaków.");
	}

	@Test
	void partsImportErrorTotalTooLarge() {
		assertThat(messageSource.getMessage("partsImport.error.totalTooLarge", new Object[] { 1234, "Rezystor 10k", 1_000_000_000L }, PL))
			.isEqualTo("Wiersz 1234: łączna ilość części „Rezystor 10k” w pliku nie może przekraczać 1000000000.");
	}

	@Test
	void partsImportErrorMoreErrors() {
		assertThat(messageSource.getMessage("partsImport.error.moreErrors", new Object[] { 1234 }, PL))
			.isEqualTo("…i 1234 więcej.");
	}

	@Test
	void partsImportErrorAmbiguousName() {
		assertThat(messageSource.getMessage("partsImport.error.ambiguousName", new Object[] { 1234, "KONDENSATOR", "Kondensator, kondensator" }, PL))
			.isEqualTo("Wiersz 1234: nazwa „KONDENSATOR” pasuje do kilku części w katalogu (Kondensator, kondensator). Zmień nazwy w katalogu tak, aby się różniły, i wgraj plik ponownie.");
	}

	@Test
	void partsImportErrorNewPartLocationRequired() {
		assertThat(messageSource.getMessage("partsImport.error.newPartLocationRequired", new Object[] { 1234, "Dioda" }, PL))
			.isEqualTo("Wiersz 1234: nowa część „Dioda” musi mieć co najmniej jedną lokalizację.");
	}

	@Test
	void partsImportErrorStockOverflow() {
		assertThat(messageSource.getMessage("partsImport.error.stockOverflow", new Object[] { 1234, "Rezystor 10k", Integer.MAX_VALUE }, PL))
			.isEqualTo("Wiersz 1234: stan części „Rezystor 10k” po imporcie przekroczyłby 2147483647.");
	}

	@Test
	void partsImportErrorPreviewExpired() {
		assertThat(messageSource.getMessage("partsImport.error.previewExpired", null, PL))
			.isEqualTo("Podgląd wygasł — wgraj plik ponownie.");
	}

	@Test
	void partsImportNoticeStale() {
		assertThat(messageSource.getMessage("partsImport.notice.stale", null, PL))
			.isEqualTo("Stany zmieniły się od podglądu — sprawdź i zatwierdź ponownie.");
	}

	@Test
	void partsImportNoticeCatalogChanged() {
		assertThat(messageSource.getMessage("partsImport.notice.catalogChanged", null, PL))
			.isEqualTo("Katalog części zmienił się w trakcie importu — sprawdź i zatwierdź ponownie.");
	}

	@Test
	void partsImportNoticeSaveFailed() {
		assertThat(messageSource.getMessage("partsImport.notice.saveFailed", null, PL))
			.isEqualTo("Nie udało się zapisać importu. Spróbuj ponownie.");
	}

	@Test
	void partsImportImported() {
		assertThat(messageSource.getMessage("partsImport.imported", new Object[] { 2, 1234, 10_000L }, PL))
			.isEqualTo("Zaimportowano: 2 nowych części, 1234 zaktualizowanych, 10000 szt.");
	}

}

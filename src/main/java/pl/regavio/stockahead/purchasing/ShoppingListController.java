package pl.regavio.stockahead.purchasing;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Manager-only shopping-list screen: delegates straight to
 * {@link ShoppingListModel#render(Model)}, same one-line shape as
 * {@code OrderController.detail}.
 */
@Controller
public class ShoppingListController {

	private final ShoppingListModel shoppingListModel;

	private final MessageSource messageSource;

	ShoppingListController(ShoppingListModel shoppingListModel, MessageSource messageSource) {
		this.shoppingListModel = shoppingListModel;
		this.messageSource = messageSource;
	}

	@GetMapping("/purchasing")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(Model model) {
		return shoppingListModel.render(model);
	}

	@GetMapping("/purchasing/export")
	@PreAuthorize("hasRole('MANAGER')")
	public ResponseEntity<byte[]> export(Locale locale) {
		byte[] csv = ShoppingListCsvWriter.write(shoppingListModel.rowsForExport(), csvHeader(locale));
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lista-zakupow.csv\"");
		return ResponseEntity.ok().headers(headers).header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
			.body(csv);
	}

	private String csvHeader(Locale locale) {
		return String.join(";", messageSource.getMessage("purchasing.csv.header.partName", null, locale),
				messageSource.getMessage("purchasing.csv.header.order", null, locale),
				messageSource.getMessage("purchasing.csv.header.requiredDate", null, locale),
				messageSource.getMessage("purchasing.csv.header.missingQuantity", null, locale));
	}

}

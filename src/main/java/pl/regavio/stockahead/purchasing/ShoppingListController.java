package pl.regavio.stockahead.purchasing;

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

	ShoppingListController(ShoppingListModel shoppingListModel) {
		this.shoppingListModel = shoppingListModel;
	}

	@GetMapping("/purchasing")
	@PreAuthorize("hasRole('MANAGER')")
	public String list(Model model) {
		return shoppingListModel.render(model);
	}

}

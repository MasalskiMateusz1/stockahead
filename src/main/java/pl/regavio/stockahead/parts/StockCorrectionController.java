package pl.regavio.stockahead.parts;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import pl.regavio.stockahead.orders.OrderLineRepository;

/**
 * Manager-only stock correction screen for one part, active or inactive:
 * the current stock, reserved and available units, a "set total" form, an
 * "adjust by delta" form, and the part's past corrections newest first.
 */
@Controller
public class StockCorrectionController {

	static final String VIEW = "parts-correction";

	private final PartRepository partRepository;

	private final OrderLineRepository orderLineRepository;

	private final StockCorrectionRepository stockCorrectionRepository;

	StockCorrectionController(PartRepository partRepository, OrderLineRepository orderLineRepository,
			StockCorrectionRepository stockCorrectionRepository) {
		this.partRepository = partRepository;
		this.orderLineRepository = orderLineRepository;
		this.stockCorrectionRepository = stockCorrectionRepository;
	}

	@GetMapping("/parts/{id}/correction")
	@PreAuthorize("hasRole('MANAGER')")
	public String form(@PathVariable Long id, Model model) {
		return renderForm(model, id, FormInput.BLANK, null);
	}

	/**
	 * Fills the model from the part's current state and re-renders the given
	 * raw inputs; 404 for an unknown part. The hidden {@code expectedQuantity}
	 * always carries the stock read here, so a re-render after a stale
	 * submission offers the fresh value.
	 */
	private String renderForm(Model model, Long id, FormInput input, String error) {
		Part part = partRepository.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		int quantity = part.getQuantity();
		int reserved = Math.toIntExact(orderLineRepository.reservedQuantityForPart(id));
		model.addAttribute("partId", part.getId());
		model.addAttribute("partName", part.getName());
		model.addAttribute("partActive", part.isActive());
		model.addAttribute("quantity", quantity);
		model.addAttribute("reserved", reserved);
		model.addAttribute("available", quantity - reserved);
		model.addAttribute("corrections", stockCorrectionRepository.findByPartIdNewestFirst(id));
		model.addAttribute("expectedQuantity", quantity);
		model.addAttribute("newQuantity", input.newQuantity());
		model.addAttribute("delta", input.delta());
		model.addAttribute("setReason", input.setReason());
		model.addAttribute("adjustReason", input.adjustReason());
		model.addAttribute("error", error);
		return VIEW;
	}

	/** Both forms' raw, untrimmed inputs, re-rendered as typed. */
	record FormInput(String newQuantity, String delta, String setReason, String adjustReason) {

		static final FormInput BLANK = new FormInput("", "", "", "");

	}

}

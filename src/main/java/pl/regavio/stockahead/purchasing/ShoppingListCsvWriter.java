package pl.regavio.stockahead.purchasing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders {@link ShoppingListModel.ShoppingListRow}s as semicolon-delimited
 * CSV bytes, UTF-8 with a leading BOM for Polish-locale Excel compatibility.
 * One row per (part, blocking order) — flattened from the aggregated read
 * model, deliberately diverging from the {@code /purchasing} screen's
 * one-row-per-part view, so each order's own quantity and date is directly
 * visible rather than packed into a shared cell. Stateless utility; every
 * field is RFC4180-escaped so a part name or project name containing
 * {@code ;}/{@code "}/newlines can never corrupt the row structure.
 */
final class ShoppingListCsvWriter {

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private ShoppingListCsvWriter() {
	}

	/**
	 * @param header the already-localized header row, e.g.
	 * {@code "Część;Zlecenie;Termin;Brakująca ilość"}
	 */
	static byte[] write(List<ShoppingListModel.ShoppingListRow> rows, String header) {
		StringBuilder csv = new StringBuilder();
		csv.append(header);
		for (ShoppingListModel.ShoppingListRow row : rows) {
			for (ShoppingListModel.BlockedOrderView blockedOrder : row.blockedOrders()) {
				csv.append("\r\n");
				csv.append(escapeField(partNameField(row)));
				csv.append(';');
				csv.append(escapeField(orderField(blockedOrder)));
				csv.append(';');
				csv.append(blockedOrder.requiredDate());
				csv.append(';');
				csv.append(blockedOrder.missingQuantity());
			}
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(UTF8_BOM);
		out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private static String partNameField(ShoppingListModel.ShoppingListRow row) {
		return row.partActive() ? row.partName() : row.partName() + " (nieaktywna)";
	}

	private static String orderField(ShoppingListModel.BlockedOrderView blockedOrder) {
		return blockedOrder.projectName() + " (#" + blockedOrder.orderId() + ")";
	}

	private static String escapeField(String field) {
		String safe = neutralizeLeadingFormulaChar(field);
		boolean needsQuoting = safe.indexOf(';') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\r') >= 0
				|| safe.indexOf('\n') >= 0;
		if (!needsQuoting) {
			return safe;
		}
		return '"' + safe.replace("\"", "\"\"") + '"';
	}

	private static String neutralizeLeadingFormulaChar(String field) {
		if (!field.isEmpty() && "=+-@".indexOf(field.charAt(0)) >= 0) {
			return "'" + field;
		}
		return field;
	}

}

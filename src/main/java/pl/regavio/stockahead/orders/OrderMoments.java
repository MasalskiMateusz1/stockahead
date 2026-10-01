package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/**
 * Formats order report/completion moments for display: date and minute in
 * the plant's local time, independent of the server's default zone. Shared
 * by {@code OrderDetailModel} and the {@code orders-list} template (as
 * {@code @orderMoments}) so both pages render the same format.
 */
@Component("orderMoments")
class OrderMoments {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.of("Europe/Warsaw"));

	/**
	 * @return the formatted moment, or {@code null} for a {@code null} moment
	 */
	public String format(Instant moment) {
		return moment == null ? null : FORMAT.format(moment);
	}

}

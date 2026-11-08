package pl.regavio.stockahead.account;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One row of the operator's company list: the company's id and name, the email of
 * its first-created manager ({@code null} when it has none), its
 * registration moment and its status.
 */
public record CompanyListRow(Long id, String name, String managerEmail, Instant createdAt, CompanyStatus status) {

	private static final ZoneId PLANT_ZONE = ZoneId.of("Europe/Warsaw");

	/** The registration date in the plant's local time. */
	public LocalDate registeredOn() {
		return LocalDate.ofInstant(createdAt, PLANT_ZONE);
	}

}

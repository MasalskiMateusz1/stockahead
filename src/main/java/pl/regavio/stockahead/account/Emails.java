package pl.regavio.stockahead.account;

import java.util.Locale;

public final class Emails {

	private Emails() {
	}

	public static String canonical(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}

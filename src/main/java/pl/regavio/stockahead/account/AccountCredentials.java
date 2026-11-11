package pl.regavio.stockahead.account;

import java.util.regex.Pattern;

/**
 * The one definition of an acceptable email and password for every form that
 * creates an account (the accounts page, setup, and later registration).
 * {@link #validate(String, String, String)} runs the checks in a fixed order
 * and reports the first failure as a message key with its arguments.
 */
public final class AccountCredentials {

	public static final int MAX_EMAIL_LENGTH = 255;

	public static final int MIN_PASSWORD_LENGTH = 12;

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private AccountCredentials() {
	}

	/**
	 * Validates the raw email, the password and its confirmation. Returns the
	 * canonical email ({@link Emails#canonical(String)}) when every check
	 * passes, otherwise the first failing message key: email required, too
	 * long, invalid, then password too short, then confirmation mismatch.
	 */
	public static Result validate(String email, String password, String confirmPassword) {
		String canonicalEmail = Emails.canonical(email);
		if (canonicalEmail.isEmpty()) {
			return Result.error("accounts.error.emailRequired");
		}
		if (canonicalEmail.length() > MAX_EMAIL_LENGTH) {
			return Result.error("accounts.error.emailTooLong", MAX_EMAIL_LENGTH);
		}
		if (!EMAIL_PATTERN.matcher(canonicalEmail).matches()) {
			return Result.error("accounts.error.emailInvalid");
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			return Result.error("accounts.error.passwordTooShort", MIN_PASSWORD_LENGTH);
		}
		if (!password.equals(confirmPassword)) {
			return Result.error("accounts.error.passwordMismatch");
		}
		return new Result(canonicalEmail, null, null);
	}

	/**
	 * The outcome of {@link #validate(String, String, String)}: either the
	 * canonical email, or the first failing message key and its arguments
	 * ({@code null} when the message takes none).
	 */
	public record Result(String canonicalEmail, String errorKey, Object[] errorArguments) {

		private static Result error(String errorKey, Object... errorArguments) {
			return new Result(null, errorKey, errorArguments.length == 0 ? null : errorArguments);
		}

		public boolean isValid() {
			return errorKey == null;
		}

	}

}

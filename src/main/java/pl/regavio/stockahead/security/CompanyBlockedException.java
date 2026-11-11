package pl.regavio.stockahead.security;

import org.springframework.security.authentication.AccountStatusException;

/**
 * Thrown by the post-authentication check when the account's company is
 * {@code BLOCKED}. It is raised only after the password matched, so the
 * blocked message never reveals that an email exists. {@link SecurityConfig}
 * maps it to {@code /login?blocked}.
 */
public class CompanyBlockedException extends AccountStatusException {

	private static final long serialVersionUID = 1L;

	public CompanyBlockedException(String message) {
		super(message);
	}

}

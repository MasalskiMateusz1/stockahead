package pl.regavio.stockahead.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal: a Spring Security {@link User} that also
 * carries the id of the account and of the account's company. Controllers
 * take the current company and the current account only from here
 * ({@code @AuthenticationPrincipal CompanyUser}), never from a request
 * parameter or form field. {@link #companyId()} is {@code null} only for the
 * operator, which belongs to no company. {@link #companyBlocked()} is a
 * login-time snapshot used only by the post-authentication check; the
 * activity filter re-reads the company's status on every request.
 */
public class CompanyUser extends User {

	private static final long serialVersionUID = 3L;

	private final Long accountId;

	private final Long companyId;

	private final boolean companyBlocked;

	public CompanyUser(String username, String password, boolean enabled,
			Collection<? extends GrantedAuthority> authorities, Long accountId, Long companyId) {
		this(username, password, enabled, authorities, accountId, companyId, false);
	}

	public CompanyUser(String username, String password, boolean enabled,
			Collection<? extends GrantedAuthority> authorities, Long accountId, Long companyId,
			boolean companyBlocked) {
		super(username, password, enabled, true, true, true, authorities);
		this.accountId = accountId;
		this.companyId = companyId;
		this.companyBlocked = companyBlocked;
	}

	public Long accountId() {
		return accountId;
	}

	/** The account's company id; {@code null} only for the operator. */
	public Long companyId() {
		return companyId;
	}

	/**
	 * Whether the account's company was {@code BLOCKED} when this principal
	 * was loaded at login; always {@code false} for the operator. Never use it
	 * to authorize a request: the status can change after login.
	 */
	public boolean companyBlocked() {
		return companyBlocked;
	}

}

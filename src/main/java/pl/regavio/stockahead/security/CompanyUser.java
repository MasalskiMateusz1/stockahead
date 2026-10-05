package pl.regavio.stockahead.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal: a Spring Security {@link User} that also
 * carries the id of the account's company. Controllers take the current
 * company only from here ({@code @AuthenticationPrincipal CompanyUser}),
 * never from a request parameter or form field.
 */
public class CompanyUser extends User {

	private final Long companyId;

	public CompanyUser(String username, String password, boolean enabled,
			Collection<? extends GrantedAuthority> authorities, Long companyId) {
		super(username, password, enabled, true, true, true, authorities);
		this.companyId = companyId;
	}

	public Long companyId() {
		return companyId;
	}

}

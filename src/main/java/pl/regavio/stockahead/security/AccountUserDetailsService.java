package pl.regavio.stockahead.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.CompanyStatus;
import pl.regavio.stockahead.account.Emails;
import pl.regavio.stockahead.account.Role;

@Service
public class AccountUserDetailsService implements UserDetailsService {

	private final AccountRepository accountRepository;

	public AccountUserDetailsService(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	/**
	 * Read-only transaction so the lazily loaded company's status can be read:
	 * the login runs in the security filter chain, outside any open session.
	 */
	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		Account account = accountRepository.findByCanonicalEmail(Emails.canonical(email))
			.orElseThrow(() -> new UsernameNotFoundException("No account with email " + email));

		Long companyId = account.getCompany() == null ? null : account.getCompany().getId();
		boolean companyBlocked = account.getCompany() != null
				&& account.getCompany().getStatus() == CompanyStatus.BLOCKED;
		return new CompanyUser(account.getEmail(), account.getPasswordHash(), account.isActive(),
				authoritiesFor(account.getRole()), account.getId(), companyId, companyBlocked);
	}

	/**
	 * The authorities a session of an account with {@code role} carries: the
	 * operator gets {@code ROLE_OPERATOR} only, a manager gets
	 * {@code ROLE_MANAGER} and {@code ROLE_TECHNICIAN}, a technician gets
	 * {@code ROLE_TECHNICIAN}. {@link AccountActivityFilter} compares a
	 * session against the same mapping.
	 */
	public static List<GrantedAuthority> authoritiesFor(Role role) {
		return switch (role) {
			case OPERATOR -> List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"));
			case MANAGER -> List.of(new SimpleGrantedAuthority("ROLE_MANAGER"),
					new SimpleGrantedAuthority("ROLE_TECHNICIAN"));
			case TECHNICIAN -> List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN"));
		};
	}

}

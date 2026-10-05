package pl.regavio.stockahead.security;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountUserDetailsServiceTest {

	@Mock
	private AccountRepository accountRepository;

	@Test
	void managerAccountGetsBothManagerAndTechnicianAuthorities() {
		Account manager = new Account();
		manager.setEmail("manager@example.com");
		manager.setPasswordHash("hashed-password");
		manager.setRole(Role.MANAGER);
		manager.setActive(true);
		manager.setCreatedAt(Instant.now());
		manager.setCompany(company(42L));

		when(accountRepository.findByCanonicalEmail("manager@example.com")).thenReturn(Optional.of(manager));

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);
		UserDetails userDetails = service.loadUserByUsername("manager@example.com");

		assertThat(userDetails.getAuthorities())
			.extracting(Object::toString)
			.contains("ROLE_MANAGER", "ROLE_TECHNICIAN");
	}

	@Test
	void technicianAccountGetsOnlyTechnicianAuthority() {
		Account technician = new Account();
		technician.setEmail("technician@example.com");
		technician.setPasswordHash("hashed-password");
		technician.setRole(Role.TECHNICIAN);
		technician.setActive(true);
		technician.setCreatedAt(Instant.now());
		technician.setCompany(company(42L));

		when(accountRepository.findByCanonicalEmail("technician@example.com")).thenReturn(Optional.of(technician));

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);
		UserDetails userDetails = service.loadUserByUsername("technician@example.com");

		assertThat(userDetails.getAuthorities())
			.extracting(Object::toString)
			.containsExactly("ROLE_TECHNICIAN");
	}

	@Test
	void principalCarriesTheAccountsCompanyId() {
		Account technician = new Account();
		technician.setEmail("technician@example.com");
		technician.setPasswordHash("hashed-password");
		technician.setRole(Role.TECHNICIAN);
		technician.setActive(true);
		technician.setCreatedAt(Instant.now());
		technician.setCompany(company(7L));

		when(accountRepository.findByCanonicalEmail("technician@example.com")).thenReturn(Optional.of(technician));

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);
		UserDetails userDetails = service.loadUserByUsername("technician@example.com");

		assertThat(userDetails).isInstanceOf(CompanyUser.class);
		assertThat(((CompanyUser) userDetails).companyId()).isEqualTo(7L);
		assertThat(userDetails.getAuthorities())
			.extracting(Object::toString)
			.containsExactly("ROLE_TECHNICIAN");
		assertThat(userDetails.isEnabled()).isTrue();
	}

	@Test
	void unknownEmailThrowsUsernameNotFoundException() {
		when(accountRepository.findByCanonicalEmail("missing@example.com")).thenReturn(Optional.empty());

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);

		assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
			.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void inactiveAccountIsDisabled() {
		Account inactive = new Account();
		inactive.setEmail("inactive@example.com");
		inactive.setPasswordHash("hashed-password");
		inactive.setRole(Role.TECHNICIAN);
		inactive.setActive(false);
		inactive.setCreatedAt(Instant.now());
		inactive.setCompany(company(42L));

		when(accountRepository.findByCanonicalEmail("inactive@example.com")).thenReturn(Optional.of(inactive));

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);
		UserDetails userDetails = service.loadUserByUsername("inactive@example.com");

		assertThat(userDetails.isEnabled()).isFalse();
	}

	@Test
	void loginEmailIsCanonicalizedBeforeLookup() {
		Account manager = new Account();
		manager.setEmail("manager@example.com");
		manager.setPasswordHash("hashed-password");
		manager.setRole(Role.MANAGER);
		manager.setActive(true);
		manager.setCreatedAt(Instant.now());
		manager.setCompany(company(42L));

		when(accountRepository.findByCanonicalEmail("manager@example.com")).thenReturn(Optional.of(manager));

		AccountUserDetailsService service = new AccountUserDetailsService(accountRepository);
		UserDetails userDetails = service.loadUserByUsername(" Manager@Example.COM ");

		assertThat(userDetails.getUsername()).isEqualTo("manager@example.com");
	}

	private static Company company(Long id) {
		Company company = new Company();
		company.setId(id);
		company.setName("Test Company");
		return company;
	}

}

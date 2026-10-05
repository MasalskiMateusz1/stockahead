package pl.regavio.stockahead;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.account.AccountRepository;
import pl.regavio.stockahead.account.Company;
import pl.regavio.stockahead.account.CompanyRepository;
import pl.regavio.stockahead.account.CompanyStatus;
import pl.regavio.stockahead.account.Role;

/**
 * The shared way tests create companies and accounts in them. Every write
 * commits in its own transaction, so MockMvc requests and other threads see
 * it. {@link #cleanUp()} removes what this fixture created, accounts first
 * and then companies (FK order); call it after the test's own deletes of
 * rows that reference accounts (orders, stock corrections).
 *
 * <p>Not picked up by component scanning: {@code @Import} it explicitly,
 * next to {@link TestcontainersConfiguration}.
 */
@TestComponent
public class CompanyFixtures {

	private final AccountRepository accountRepository;

	private final CompanyRepository companyRepository;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final Set<Long> companyIds = ConcurrentHashMap.newKeySet();

	private final Set<String> emails = ConcurrentHashMap.newKeySet();

	public CompanyFixtures(AccountRepository accountRepository, CompanyRepository companyRepository,
			PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate,
			PlatformTransactionManager transactionManager) {
		this.accountRepository = accountRepository;
		this.companyRepository = companyRepository;
		this.passwordEncoder = passwordEncoder;
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Creates and commits an {@code ACTIVE} company. */
	public Company company(String name) {
		Company saved = transactionTemplate.execute(status -> {
			Company company = new Company();
			company.setName(name);
			company.setStatus(CompanyStatus.ACTIVE);
			company.setCreatedAt(Instant.now());
			return companyRepository.save(company);
		});
		companyIds.add(saved.getId());
		return saved;
	}

	/**
	 * Creates and commits an account in {@code company}, with the password
	 * encoded as the login form expects.
	 */
	public Account account(Company company, String email, String rawPassword, Role role, boolean active) {
		emails.add(email);
		return transactionTemplate.execute(status -> {
			Account account = new Account();
			account.setEmail(email);
			account.setPasswordHash(passwordEncoder.encode(rawPassword));
			account.setRole(role);
			account.setActive(active);
			account.setCreatedAt(Instant.now());
			account.setCompany(companyRepository.getReferenceById(company.getId()));
			return accountRepository.save(account);
		});
	}

	/**
	 * Deletes the accounts this fixture created (by email), any other
	 * account left in a company it created (e.g. a technician created
	 * through the UI), and then those companies.
	 */
	public void cleanUp() {
		List<String> createdEmails = new ArrayList<>(emails);
		List<Long> createdCompanyIds = new ArrayList<>(companyIds);
		transactionTemplate.executeWithoutResult(status -> {
			for (String email : createdEmails) {
				jdbcTemplate.update("DELETE FROM accounts WHERE lower(btrim(email)) = lower(btrim(?))", email);
			}
			for (Long companyId : createdCompanyIds) {
				jdbcTemplate.update("DELETE FROM accounts WHERE company_id = ?", companyId);
				jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId);
			}
		});
		emails.removeAll(createdEmails);
		companyIds.removeAll(createdCompanyIds);
	}

}

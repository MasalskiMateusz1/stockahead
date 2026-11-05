package pl.regavio.stockahead.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByEmail(String email);

	@Query(value = "SELECT * FROM accounts WHERE lower(btrim(email)) = :canonicalEmail", nativeQuery = true)
	Optional<Account> findByCanonicalEmail(@Param("canonicalEmail") String canonicalEmail);

	boolean existsByRole(Role role);

	/**
	 * Waits for and takes a transaction-scoped advisory lock; see
	 * {@link SetupController} for the setup-only key.
	 */
	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
	Integer lockAdvisory(@Param("namespace") int namespace, @Param("key") int key);

	List<Account> findByCompanyIdOrderByEmailAsc(Long companyId);

	/**
	 * Active accounts of {@code role} in company {@code companyId}, other than
	 * {@code excludedId}. Read it under the company row lock
	 * ({@link CompanyRepository#findByIdForUpdate(Long)}).
	 */
	long countByCompanyIdAndRoleAndActiveTrueAndIdNot(Long companyId, Role role, Long excludedId);

	Optional<Account> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * {@link #findByIdAndCompanyId(Long, Long)} with the account's company
	 * fetched in the same query, so its status can be read outside a
	 * persistence session (the account-activity filter's company gate).
	 */
	@Query("SELECT a FROM Account a JOIN FETCH a.company c WHERE a.id = :id AND c.id = :companyId")
	Optional<Account> findWithCompanyByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

	/**
	 * The operator's counterpart of {@link #findByIdAndCompanyId(Long, Long)}:
	 * loads account {@code id} only if it belongs to no company. Only the
	 * {@link Role#OPERATOR} account has no company.
	 */
	Optional<Account> findByIdAndCompanyIdIsNull(Long id);

	/**
	 * The accounts of company {@code companyId} an order may be assigned to:
	 * active technicians and managers, by email.
	 */
	@Query("SELECT a FROM Account a WHERE a.company.id = :companyId AND a.active = true AND a.role IN (pl.regavio.stockahead.account.Role.TECHNICIAN, pl.regavio.stockahead.account.Role.MANAGER) ORDER BY a.email")
	List<Account> findAssignable(@Param("companyId") Long companyId);

}

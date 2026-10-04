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

	List<Account> findByRoleOrderByEmailAsc(Role role);

	/**
	 * The accounts an order may be assigned to: active technicians and the
	 * manager, by email.
	 */
	@Query("SELECT a FROM Account a WHERE a.active = true AND a.role IN (pl.regavio.stockahead.account.Role.TECHNICIAN, pl.regavio.stockahead.account.Role.MANAGER) ORDER BY a.email")
	List<Account> findAssignable();

}

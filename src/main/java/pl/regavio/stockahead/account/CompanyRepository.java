package pl.regavio.stockahead.account;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	/**
	 * Locks one company row for the rest of the transaction: the per-company
	 * mutex for account transitions that could remove the last active manager.
	 * On Postgres Hibernate emits {@code FOR NO KEY UPDATE}, which does not
	 * block foreign-key inserts into company-scoped tables; do not replace it
	 * with a native {@code SELECT … FOR UPDATE}.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM Company c WHERE c.id = :id")
	Optional<Company> findByIdForUpdate(@Param("id") Long id);

}

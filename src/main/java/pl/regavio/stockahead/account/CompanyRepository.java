package pl.regavio.stockahead.account;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	/**
	 * Locks one company row for the rest of the transaction: the per-company
	 * mutex for account writes (the last-active-manager rule and the caller re-check).
	 * On Postgres Hibernate emits {@code FOR NO KEY UPDATE}, which does not
	 * block foreign-key inserts into company-scoped tables; do not replace it
	 * with a native {@code SELECT … FOR UPDATE}.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM Company c WHERE c.id = :id")
	Optional<Company> findByIdForUpdate(@Param("id") Long id);

	/**
	 * Every company with the email of its first-created manager (smallest
	 * {@code (created_at, id)}, active or not; {@code null} when the company
	 * has no manager), newest company first. This is the intentional
	 * cross-company read behind the operator's company list
	 * ({@link OperatorCompanyController}); never use it from a company-scoped
	 * route.
	 */
	@Query("""
			SELECT new pl.regavio.stockahead.account.CompanyListRow(c.id, c.name,
				(SELECT a.email FROM Account a
					WHERE a.company = c AND a.role = pl.regavio.stockahead.account.Role.MANAGER
					ORDER BY a.createdAt, a.id LIMIT 1),
				c.createdAt, c.status)
			FROM Company c
			ORDER BY c.createdAt DESC, c.id DESC
			""")
	List<CompanyListRow> findAllForOperatorList();

	/**
	 * The by-id variant of {@link #findAllForOperatorList()}: company
	 * {@code id} with its first-created manager's email, or empty if no
	 * company has that id. Like the list, an intentional cross-company read
	 * used only by the operator's routes ({@link OperatorCompanyController}).
	 */
	@Query("""
			SELECT new pl.regavio.stockahead.account.CompanyListRow(c.id, c.name,
				(SELECT a.email FROM Account a
					WHERE a.company = c AND a.role = pl.regavio.stockahead.account.Role.MANAGER
					ORDER BY a.createdAt, a.id LIMIT 1),
				c.createdAt, c.status)
			FROM Company c
			WHERE c.id = :id
			""")
	Optional<CompanyListRow> findOperatorRowById(@Param("id") Long id);

}

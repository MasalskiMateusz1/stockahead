package pl.regavio.stockahead.orders;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** Company {@code companyId}'s orders in {@code status}. */
	@Query("SELECT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.assignee WHERE o.status = :status AND o.companyId = :companyId")
	List<Order> findByStatusWithProject(@Param("companyId") Long companyId, @Param("status") OrderStatus status);

	/** Company {@code companyId}'s orders in {@code status} assigned to account {@code assigneeId}. */
	@Query("SELECT o FROM Order o JOIN FETCH o.project JOIN FETCH o.assignee a WHERE o.status = :status AND o.companyId = :companyId AND a.id = :assigneeId")
	List<Order> findByStatusAndAssigneeWithProject(@Param("companyId") Long companyId,
			@Param("status") OrderStatus status, @Param("assigneeId") Long assigneeId);

	/**
	 * Company {@code companyId}'s orders in {@code status} with no assignee or
	 * an inactive one — the complement of the assignable rule ("Nieprzypisane").
	 */
	@Query("SELECT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.assignee a WHERE o.status = :status AND o.companyId = :companyId AND (a IS NULL OR a.active = false)")
	List<Order> findByStatusUnassignedWithProject(@Param("companyId") Long companyId,
			@Param("status") OrderStatus status);

	@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.lines l LEFT JOIN FETCH l.part WHERE o.id = :id")
	Optional<Order> findByIdWithDetails(@Param("id") Long id);

	/**
	 * {@link #findByIdWithDetails(Long)} scoped to company {@code companyId}:
	 * empty for another company's order, exactly like a missing id.
	 */
	@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.lines l LEFT JOIN FETCH l.part WHERE o.id = :id AND o.companyId = :companyId")
	Optional<Order> findByIdWithDetails(@Param("id") Long id, @Param("companyId") Long companyId);

	/** Locks order {@code id} if it belongs to company {@code companyId}; empty otherwise. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id AND o.companyId = :companyId")
	Optional<Order> findByIdForUpdate(@Param("id") Long id, @Param("companyId") Long companyId);

	/** The order {@code id} if it belongs to company {@code companyId}; empty otherwise, exactly like a missing id. */
	Optional<Order> findByIdAndCompanyId(Long id, Long companyId);

}

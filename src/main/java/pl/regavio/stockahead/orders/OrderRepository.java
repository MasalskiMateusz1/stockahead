package pl.regavio.stockahead.orders;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("SELECT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.assignee WHERE o.status = :status")
	List<Order> findByStatusWithProject(@Param("status") OrderStatus status);

	/** Orders in {@code status} assigned to account {@code assigneeId}. */
	@Query("SELECT o FROM Order o JOIN FETCH o.project JOIN FETCH o.assignee a WHERE o.status = :status AND a.id = :assigneeId")
	List<Order> findByStatusAndAssigneeWithProject(@Param("status") OrderStatus status,
			@Param("assigneeId") Long assigneeId);

	/**
	 * Orders in {@code status} with no assignee or an inactive one — the
	 * complement of the assignable rule ("Nieprzypisane").
	 */
	@Query("SELECT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.assignee a WHERE o.status = :status AND (a IS NULL OR a.active = false)")
	List<Order> findByStatusUnassignedWithProject(@Param("status") OrderStatus status);

	@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.lines l LEFT JOIN FETCH l.part WHERE o.id = :id")
	Optional<Order> findByIdWithDetails(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id") Long id);

}

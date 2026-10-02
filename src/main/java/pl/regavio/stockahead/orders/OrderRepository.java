package pl.regavio.stockahead.orders;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("SELECT o FROM Order o JOIN FETCH o.project WHERE o.status = :status")
	List<Order> findByStatusWithProject(@Param("status") OrderStatus status);

	@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.project LEFT JOIN FETCH o.lines l LEFT JOIN FETCH l.part WHERE o.id = :id")
	Optional<Order> findByIdWithDetails(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id") Long id);

}

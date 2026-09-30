package pl.regavio.stockahead.orders;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

	@Query("SELECT ol.part.id, SUM(ol.reservedQuantity) FROM OrderLine ol WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN GROUP BY ol.part.id")
	List<Object[]> reservedQuantitiesByPart();

}

package pl.regavio.stockahead.orders;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

	@Query("SELECT ol.part.id, SUM(ol.reservedQuantity) FROM OrderLine ol WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN GROUP BY ol.part.id")
	List<Object[]> reservedQuantitiesByPart();

	@Query("SELECT ol FROM OrderLine ol JOIN FETCH ol.order JOIN FETCH ol.part "
			+ "WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN AND ol.part.id IN :partIds")
	List<OrderLine> findOpenLinesForParts(@Param("partIds") Collection<Long> partIds);

	@Query("SELECT ol FROM OrderLine ol JOIN FETCH ol.order o JOIN FETCH o.project JOIN FETCH ol.part "
			+ "WHERE o.status = pl.regavio.stockahead.orders.OrderStatus.OPEN "
			+ "AND ol.requiredQuantity > ol.reservedQuantity")
	List<OrderLine> findOpenLinesWithShortage();

}

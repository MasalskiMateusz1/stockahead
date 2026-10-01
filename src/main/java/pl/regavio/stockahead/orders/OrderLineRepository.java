package pl.regavio.stockahead.orders;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

	@Query("SELECT ol.part.id, SUM(ol.reservedQuantity) FROM OrderLine ol WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN GROUP BY ol.part.id")
	List<Object[]> reservedQuantitiesByPart();

	/**
	 * Resolves the part id for a line without hydrating the {@link Order}/
	 * {@link OrderLine} entities into the persistence context — unlike
	 * {@code Order.getLines()}, a scalar projection isn't cached in
	 * Hibernate's first-level cache, so a caller that needs this purely to
	 * learn which part to lock before a later, genuinely fresh entity read
	 * (e.g. {@code PickingController.pick}, under {@code open-in-view}'s
	 * one-persistence-context-per-request sharing) doesn't poison that later
	 * read with a stale cached instance. Empty when the order doesn't exist
	 * or the line isn't one of its own.
	 */
	@Query("SELECT ol.part.id FROM OrderLine ol WHERE ol.id = :lineId AND ol.order.id = :orderId")
	Optional<Long> findPartIdForLine(@Param("orderId") Long orderId, @Param("lineId") Long lineId);

	@Query("SELECT ol FROM OrderLine ol JOIN FETCH ol.order JOIN FETCH ol.part "
			+ "WHERE ol.order.status = pl.regavio.stockahead.orders.OrderStatus.OPEN AND ol.part.id IN :partIds")
	List<OrderLine> findOpenLinesForParts(@Param("partIds") Collection<Long> partIds);

	@Query("SELECT ol FROM OrderLine ol JOIN FETCH ol.order o JOIN FETCH o.project JOIN FETCH ol.part "
			+ "WHERE o.status = pl.regavio.stockahead.orders.OrderStatus.OPEN "
			+ "AND ol.requiredQuantity > ol.reservedQuantity")
	List<OrderLine> findOpenLinesWithShortage();

}

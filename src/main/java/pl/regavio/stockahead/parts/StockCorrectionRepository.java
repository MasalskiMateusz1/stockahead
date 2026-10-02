package pl.regavio.stockahead.parts;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCorrectionRepository extends JpaRepository<StockCorrection, Long> {

	/** A part's corrections, newest first, with each author fetched in the same query. */
	@Query("SELECT c FROM StockCorrection c JOIN FETCH c.account WHERE c.part.id = :partId "
			+ "ORDER BY c.createdAt DESC, c.id DESC")
	List<StockCorrection> findByPartIdNewestFirst(@Param("partId") Long partId);

}

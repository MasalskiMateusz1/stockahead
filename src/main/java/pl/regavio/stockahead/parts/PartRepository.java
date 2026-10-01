package pl.regavio.stockahead.parts;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PartRepository extends JpaRepository<Part, Long> {

	Optional<Part> findByName(String name);

	@Query("""
		SELECT DISTINCT p FROM Part p LEFT JOIN p.locations l
		WHERE (:showInactive = true OR p.active = true)
		AND (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
			OR LOWER(l.location) LIKE LOWER(CONCAT('%', :q, '%')))
		ORDER BY p.name
		""")
	List<Part> search(@Param("q") String q, @Param("showInactive") boolean showInactive);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Part p WHERE p.id IN :ids ORDER BY p.id")
	List<Part> findByIdInForUpdate(@Param("ids") Collection<Long> ids);

}

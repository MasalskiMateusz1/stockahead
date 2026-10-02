package pl.regavio.stockahead.parts;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

	/** Every distinct location in use across all parts, as plain strings (no {@link Part} hydrated). */
	@Query("SELECT DISTINCT l.location FROM PartLocation l ORDER BY l.location")
	List<String> findAllLocationNames();

	/**
	 * Every stored spelling of the shelf {@code location} names, matched
	 * case-insensitively, oldest row first. The first element is the shelf's
	 * canonical spelling; see {@link LocationSpellings}.
	 */
	@Query("SELECT l.location FROM PartLocation l WHERE LOWER(l.location) = LOWER(:location) ORDER BY l.id")
	List<String> findSpellingsOf(@Param("location") String location);

	/**
	 * Re-spells the shelf {@code spelling} names (matched case-insensitively)
	 * on every part except {@code partId}. A bulk update that bypasses the
	 * persistence context: flush the edited part's own row first and never
	 * include it here, or its managed entity would go stale.
	 */
	@Modifying
	@Query("""
		UPDATE PartLocation l SET l.location = :spelling
		WHERE LOWER(l.location) = LOWER(:spelling) AND l.location <> :spelling AND l.part.id <> :partId
		""")
	int respellOnOtherParts(@Param("spelling") String spelling, @Param("partId") Long partId);

	/**
	 * Waits for and takes a transaction-scoped advisory lock on one shelf;
	 * see {@link LocationSpellings#lockShelves}.
	 */
	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
	Integer lockShelf(@Param("namespace") int namespace, @Param("key") int key);

	@Query("SELECT DISTINCT p FROM Part p LEFT JOIN FETCH p.locations WHERE p.id IN :ids")
	List<Part> findWithLocationsByIdIn(@Param("ids") Collection<Long> ids);

	/**
	 * Current {@code (id, quantity)} pairs for the given parts, as a plain
	 * scalar projection rather than hydrated {@link Part} entities. Unlike
	 * {@link #findByIdInForUpdate(Collection)}, a scalar row never goes
	 * through Hibernate's first-level-cache reconciliation — if the caller's
	 * transaction already has one of these {@code Part}s managed from an
	 * earlier, unrelated read (e.g. a lazy {@code @ManyToOne} touched before
	 * the lock was acquired), a second full-entity query for the same id
	 * would just hand back that already-managed (and potentially stale)
	 * instance, not the DB's current row. Call this only after locking the
	 * same ids via {@code findByIdInForUpdate}, so the values read here are
	 * guaranteed current as of that lock.
	 */
	@Query("SELECT p.id, p.quantity FROM Part p WHERE p.id IN :ids")
	List<Object[]> findCurrentQuantities(@Param("ids") Collection<Long> ids);

}

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

	/** The part {@code id} if it belongs to company {@code companyId}; empty otherwise, exactly like a missing id. */
	Optional<Part> findByIdAndCompanyId(Long id, Long companyId);

	Optional<Part> findByCompanyIdAndName(Long companyId, String name);

	@Query("""
		SELECT DISTINCT p FROM Part p LEFT JOIN p.locations l
		WHERE p.company.id = :companyId
		AND (:showInactive = true OR p.active = true)
		AND (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
			OR LOWER(l.location) LIKE LOWER(CONCAT('%', :q, '%')))
		ORDER BY p.name
		""")
	List<Part> search(@Param("companyId") Long companyId, @Param("q") String q,
			@Param("showInactive") boolean showInactive);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Part p WHERE p.id IN :ids ORDER BY p.id")
	List<Part> findByIdInForUpdate(@Param("ids") Collection<Long> ids);

	/**
	 * Locks the parts among {@code ids} that belong to company
	 * {@code companyId}, in id order; a part of another company is neither
	 * returned nor locked (a {@code FOR UPDATE} only locks, and waits on, rows
	 * that pass its {@code WHERE}), so one company never waits on another's
	 * part locks. See {@link pl.regavio.stockahead.orders.ReservationAllocator}.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Part p WHERE p.id IN :ids AND p.company.id = :companyId ORDER BY p.id")
	List<Part> findByIdInAndCompanyIdForUpdate(@Param("ids") Collection<Long> ids,
			@Param("companyId") Long companyId);

	/**
	 * Every distinct location in use across company {@code companyId}'s parts,
	 * as plain strings (no {@link Part} hydrated).
	 */
	@Query("SELECT DISTINCT l.location FROM PartLocation l WHERE l.companyId = :companyId ORDER BY l.location")
	List<String> findAllLocationNames(@Param("companyId") Long companyId);

	/**
	 * Every spelling of the shelf {@code location} names stored in company
	 * {@code companyId}, matched case-insensitively, oldest row first. The
	 * first element is the shelf's canonical spelling in that company; see
	 * {@link LocationSpellings}.
	 */
	@Query("""
		SELECT l.location FROM PartLocation l
		WHERE l.companyId = :companyId AND LOWER(l.location) = LOWER(:location)
		ORDER BY l.id
		""")
	List<String> findSpellingsOf(@Param("companyId") Long companyId, @Param("location") String location);

	/**
	 * Re-spells the shelf {@code spelling} names (matched case-insensitively)
	 * on every part of company {@code companyId} except {@code partId}; other
	 * companies' shelves are never touched. A bulk update that bypasses the
	 * persistence context: flush the edited part's own row first and never
	 * include it here, or its managed entity would go stale.
	 */
	@Modifying
	@Query("""
		UPDATE PartLocation l SET l.location = :spelling
		WHERE l.companyId = :companyId AND LOWER(l.location) = LOWER(:spelling) AND l.location <> :spelling
		AND l.part.id <> :partId
		""")
	int respellOnOtherParts(@Param("companyId") Long companyId, @Param("spelling") String spelling,
			@Param("partId") Long partId);

	/**
	 * Waits for and takes a transaction-scoped advisory lock on one shelf or
	 * one part name; see {@link LocationSpellings#lockShelves} and
	 * {@link LocationSpellings#lockPartNames}.
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
	 * same ids via {@code findByIdInAndCompanyIdForUpdate}, so the values
	 * read here are guaranteed current as of that lock. Only parts of company
	 * {@code companyId} are read; another company's ids are skipped.
	 */
	@Query("SELECT p.id, p.quantity FROM Part p WHERE p.id IN :ids AND p.company.id = :companyId")
	List<Object[]> findCurrentQuantities(@Param("companyId") Long companyId, @Param("ids") Collection<Long> ids);

	/**
	 * Every {@code (id, name)} pair of company {@code companyId}'s parts as a
	 * plain scalar projection (no {@link Part} hydrated), for matching names
	 * in Java; see {@link CatalogNameIndex}.
	 */
	@Query("SELECT p.id, p.name FROM Part p WHERE p.company.id = :companyId")
	List<Object[]> findAllIdsAndNames(@Param("companyId") Long companyId);

}

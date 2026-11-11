package pl.regavio.stockahead.projects;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

	/** Company {@code companyId}'s project {@code id}; another company's project is empty, like a missing one. */
	Optional<Project> findByIdAndCompanyId(Long id, Long companyId);

	boolean existsByIdAndCompanyId(Long id, Long companyId);

	@Query("""
		SELECT p FROM Project p
		WHERE p.company.id = :companyId
		AND (:showInactive = true OR p.active = true)
		ORDER BY p.name
		""")
	List<Project> list(@Param("companyId") Long companyId, @Param("showInactive") boolean showInactive);

}

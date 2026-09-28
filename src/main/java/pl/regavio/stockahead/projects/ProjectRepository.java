package pl.regavio.stockahead.projects;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

	@Query("""
		SELECT p FROM Project p
		WHERE (:showInactive = true OR p.active = true)
		ORDER BY p.name
		""")
	List<Project> list(@Param("showInactive") boolean showInactive);

}

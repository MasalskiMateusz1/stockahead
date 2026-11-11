package pl.regavio.stockahead.projects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import pl.regavio.stockahead.parts.Part;

@Entity
@Table(name = "bom_lines")
public class BomLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne
	@JoinColumn(name = "part_id", nullable = false)
	private Part part;

	/**
	 * The project's company, set by {@link Project#addBomLine}. A plain column:
	 * {@link #project} and {@link #part} stay the only writers of
	 * {@code project_id}/{@code part_id}, and the composite
	 * {@code (project_id, company_id)} and {@code (part_id, company_id)}
	 * foreign keys keep the three in step.
	 */
	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "quantity_per_unit", nullable = false)
	private int quantityPerUnit;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Project getProject() {
		return project;
	}

	public void setProject(Project project) {
		this.project = project;
	}

	public Part getPart() {
		return part;
	}

	public void setPart(Part part) {
		this.part = part;
	}

	public Long getCompanyId() {
		return companyId;
	}

	void setCompanyId(Long companyId) {
		this.companyId = companyId;
	}

	public int getQuantityPerUnit() {
		return quantityPerUnit;
	}

	public void setQuantityPerUnit(int quantityPerUnit) {
		this.quantityPerUnit = quantityPerUnit;
	}

}

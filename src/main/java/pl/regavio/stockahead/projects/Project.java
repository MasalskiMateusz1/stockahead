package pl.regavio.stockahead.projects;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import pl.regavio.stockahead.account.Company;

@Entity
@Table(name = "projects")
public class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<BomLine> bomLines = new ArrayList<>();

	@OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProjectLink> links = new ArrayList<>();

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Company getCompany() {
		return company;
	}

	/**
	 * Sets the owning company; BOM lines already added take it too, since a
	 * BOM line always carries its project's company.
	 */
	public void setCompany(Company company) {
		this.company = company;
		for (BomLine bomLine : bomLines) {
			bomLine.setCompanyId(companyId());
		}
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public List<BomLine> getBomLines() {
		return bomLines;
	}

	public void addBomLine(BomLine bomLine) {
		bomLines.add(bomLine);
		bomLine.setProject(this);
		bomLine.setCompanyId(companyId());
	}

	private Long companyId() {
		return company == null ? null : company.getId();
	}

	public void removeBomLine(BomLine bomLine) {
		bomLines.remove(bomLine);
		bomLine.setProject(null);
	}

	public List<ProjectLink> getLinks() {
		return links;
	}

	public void addLink(ProjectLink link) {
		links.add(link);
		link.setProject(this);
	}

	public void removeLink(ProjectLink link) {
		links.remove(link);
		link.setProject(null);
	}

}

package pl.regavio.stockahead.projects;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String name;

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

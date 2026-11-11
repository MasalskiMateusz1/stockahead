package pl.regavio.stockahead.parts;

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
@Table(name = "parts")
public class Part {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@Column(nullable = false)
	private int quantity;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "part", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<PartLocation> locations = new ArrayList<>();

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
	 * Sets the owning company; locations already added take it too, since a
	 * location always carries its part's company.
	 */
	public void setCompany(Company company) {
		this.company = company;
		for (PartLocation location : locations) {
			location.setCompanyId(companyId());
		}
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
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

	public List<PartLocation> getLocations() {
		return locations;
	}

	public void addLocation(PartLocation location) {
		locations.add(location);
		location.setPart(this);
		location.setCompanyId(companyId());
	}

	private Long companyId() {
		return company == null ? null : company.getId();
	}

	public void removeLocation(PartLocation location) {
		locations.remove(location);
		location.setPart(null);
	}

}

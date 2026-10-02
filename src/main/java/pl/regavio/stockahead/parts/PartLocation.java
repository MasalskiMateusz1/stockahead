package pl.regavio.stockahead.parts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "part_locations")
public class PartLocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "part_id", nullable = false)
	private Part part;

	@Column(nullable = false)
	private String location;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Part getPart() {
		return part;
	}

	public void setPart(Part part) {
		this.part = part;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	/**
	 * Locations name the same shelf regardless of letter case ("Regał A1" and
	 * "regał a1"). Every place that decides whether a part already has a
	 * location, or whether two entered locations are duplicates, compares
	 * through this.
	 */
	static boolean sameLocation(String first, String second) {
		return normalize(first).equalsIgnoreCase(normalize(second));
	}

	/**
	 * Turns a typed or pasted location into its stored form: non-breaking
	 * spaces (U+00A0, U+2007, U+202F, common in values copied from a
	 * spreadsheet) become plain spaces, then leading and trailing whitespace
	 * is stripped. {@link String#trim()} and {@link String#strip()} both keep
	 * non-breaking spaces, so a pasted "A1" with a trailing one would
	 * otherwise be a second shelf.
	 */
	static String normalize(String location) {
		return location.replaceAll("[\\u00A0\\u2007\\u202F]", " ").strip();
	}

}

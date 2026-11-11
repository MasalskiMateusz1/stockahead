package pl.regavio.stockahead.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Unique among accounts whose email is not released
	 * ({@code accounts_email_canonical_idx}, case and surrounding spaces
	 * ignored); a released account keeps its email, which a live account may
	 * share.
	 */
	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** The account's company; {@code null} only for the {@link Role#OPERATOR}. */
	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "company_id", nullable = true)
	private Company company;

	/**
	 * When this account's email was released, or {@code null} if it was not.
	 * A released account belongs to a rejected registration and never logs
	 * in: login ignores it, its email is free for a new account, and the
	 * database keeps it inactive and never an operator.
	 */
	@Column(name = "email_released_at")
	private Instant emailReleasedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
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

	public Company getCompany() {
		return company;
	}

	public void setCompany(Company company) {
		this.company = company;
	}

	public Instant getEmailReleasedAt() {
		return emailReleasedAt;
	}

	public void setEmailReleasedAt(Instant emailReleasedAt) {
		this.emailReleasedAt = emailReleasedAt;
	}

	/** Whether this account's email was released ({@link #getEmailReleasedAt()} is set). */
	public boolean isEmailReleased() {
		return emailReleasedAt != null;
	}

}

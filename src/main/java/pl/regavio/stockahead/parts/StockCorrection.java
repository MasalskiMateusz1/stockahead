package pl.regavio.stockahead.parts;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import pl.regavio.stockahead.account.Account;

/**
 * One manual stock correction of a part: the stock before and after, the
 * mandatory reason and the manager who made it. Rows are only ever inserted.
 */
@Entity
@Table(name = "stock_corrections")
public class StockCorrection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "part_id", nullable = false)
	private Part part;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Column(name = "quantity_before", nullable = false)
	private int quantityBefore;

	@Column(name = "quantity_after", nullable = false)
	private int quantityAfter;

	@Column(nullable = false, length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

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

	public Account getAccount() {
		return account;
	}

	public void setAccount(Account account) {
		this.account = account;
	}

	public int getQuantityBefore() {
		return quantityBefore;
	}

	public void setQuantityBefore(int quantityBefore) {
		this.quantityBefore = quantityBefore;
	}

	public int getQuantityAfter() {
		return quantityAfter;
	}

	public void setQuantityAfter(int quantityAfter) {
		this.quantityAfter = quantityAfter;
	}

	/** {@code quantityAfter - quantityBefore}: negative when stock was counted down. */
	public int getDelta() {
		return quantityAfter - quantityBefore;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}

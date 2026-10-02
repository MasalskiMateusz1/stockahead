package pl.regavio.stockahead.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import pl.regavio.stockahead.account.Account;
import pl.regavio.stockahead.projects.Project;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@Column(name = "quantity_units", nullable = false)
	private int quantityUnits;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Priority priority;

	@Column(name = "required_date", nullable = false)
	private LocalDate requiredDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status = OrderStatus.OPEN;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "taken_at")
	private Instant takenAt;

	@Column(name = "completion_reported_at")
	private Instant completionReportedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "completion_reported_by")
	private Account completionReportedBy;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cancelled_by")
	private Account cancelledBy;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderLine> lines = new ArrayList<>();

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

	public int getQuantityUnits() {
		return quantityUnits;
	}

	public void setQuantityUnits(int quantityUnits) {
		this.quantityUnits = quantityUnits;
	}

	public Priority getPriority() {
		return priority;
	}

	public void setPriority(Priority priority) {
		this.priority = priority;
	}

	public LocalDate getRequiredDate() {
		return requiredDate;
	}

	public void setRequiredDate(LocalDate requiredDate) {
		this.requiredDate = requiredDate;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getTakenAt() {
		return takenAt;
	}

	public void setTakenAt(Instant takenAt) {
		this.takenAt = takenAt;
	}

	public boolean isTaken() {
		return takenAt != null;
	}

	public Instant getCompletionReportedAt() {
		return completionReportedAt;
	}

	public void setCompletionReportedAt(Instant completionReportedAt) {
		this.completionReportedAt = completionReportedAt;
	}

	public Account getCompletionReportedBy() {
		return completionReportedBy;
	}

	public void setCompletionReportedBy(Account completionReportedBy) {
		this.completionReportedBy = completionReportedBy;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public void setCancelledAt(Instant cancelledAt) {
		this.cancelledAt = cancelledAt;
	}

	public Account getCancelledBy() {
		return cancelledBy;
	}

	public void setCancelledBy(Account cancelledBy) {
		this.cancelledBy = cancelledBy;
	}

	public boolean isCompletionReported() {
		return completionReportedAt != null;
	}

	/**
	 * True when the order may be reported as finished: still {@code OPEN},
	 * already taken (first pick done), and not reported yet.
	 */
	public boolean canReportCompletion() {
		return status == OrderStatus.OPEN && isTaken() && !isCompletionReported();
	}

	/**
	 * True when the manager may cancel the order: still {@code OPEN} and not
	 * reported as finished (a pending report must be rejected first).
	 */
	public boolean canCancel() {
		return status == OrderStatus.OPEN && !isCompletionReported();
	}

	public List<OrderLine> getLines() {
		return lines;
	}

	public void addLine(OrderLine line) {
		lines.add(line);
		line.setOrder(this);
	}

	public void removeLine(OrderLine line) {
		lines.remove(line);
		line.setOrder(null);
	}

}

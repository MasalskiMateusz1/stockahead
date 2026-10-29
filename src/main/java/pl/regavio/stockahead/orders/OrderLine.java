package pl.regavio.stockahead.orders;

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
@Table(name = "order_lines")
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@ManyToOne
	@JoinColumn(name = "part_id", nullable = false)
	private Part part;

	/**
	 * The order's company, set by {@link Order#addLine}. A plain column:
	 * {@link #order} and {@link #part} stay the only writers of
	 * {@code order_id}/{@code part_id}, and the composite
	 * {@code (order_id, company_id)} and {@code (part_id, company_id)}
	 * foreign keys keep the three in step.
	 */
	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "required_quantity", nullable = false)
	private int requiredQuantity;

	@Column(name = "reserved_quantity", nullable = false)
	private int reservedQuantity;

	@Column(name = "picked_quantity", nullable = false)
	private int pickedQuantity = 0;

	@Column(name = "returned_quantity", nullable = false)
	private int returnedQuantity = 0;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Order getOrder() {
		return order;
	}

	public void setOrder(Order order) {
		this.order = order;
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

	public int getRequiredQuantity() {
		return requiredQuantity;
	}

	public void setRequiredQuantity(int requiredQuantity) {
		this.requiredQuantity = requiredQuantity;
	}

	public int getReservedQuantity() {
		return reservedQuantity;
	}

	public void setReservedQuantity(int reservedQuantity) {
		this.reservedQuantity = reservedQuantity;
	}

	public int getPickedQuantity() {
		return pickedQuantity;
	}

	public void setPickedQuantity(int pickedQuantity) {
		this.pickedQuantity = pickedQuantity;
	}

	/**
	 * Picked units that went back into stock when the order was cancelled;
	 * never more than {@code pickedQuantity} (cumulative), the rest count as
	 * used.
	 */
	public int getReturnedQuantity() {
		return returnedQuantity;
	}

	public void setReturnedQuantity(int returnedQuantity) {
		this.returnedQuantity = returnedQuantity;
	}

	/**
	 * Units still neither reserved nor picked — what the shopping list must
	 * buy. {@code reservedQuantity} is live (a pick moves units out of it into
	 * {@code pickedQuantity}), so picked units must be subtracted too, or
	 * every pick would reappear as a shortage.
	 */
	public int getMissingQuantity() {
		return requiredQuantity - reservedQuantity - pickedQuantity;
	}

}

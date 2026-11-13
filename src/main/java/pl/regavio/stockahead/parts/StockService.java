package pl.regavio.stockahead.parts;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import pl.regavio.stockahead.orders.ReservationAllocator;

/** Serializes physical stock changes on the company-owned part row. */
@Service
public class StockService {

	private final PartRepository parts;
	private final EntityManager entityManager;
	private final ReservationAllocator allocator;

	StockService(PartRepository parts, EntityManager entityManager, ReservationAllocator allocator) {
		this.parts = parts;
		this.entityManager = entityManager;
		this.allocator = allocator;
	}

	/**
	 * Locks company {@code companyId}'s part and returns it with current
	 * stock. A locking query does not overwrite an instance the transaction
	 * already manages, so the part and its locations (cascaded) are refreshed
	 * after the lock; other pending changes in the transaction are kept.
	 */
	public Part lock(Long companyId, Long partId) {
		Part part = parts.findByIdInAndCompanyIdForUpdate(Set.of(partId), companyId).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		entityManager.refresh(part);
		return part;
	}

	public PartLocation location(Part lockedPart, Long companyId, Long locationId) {
		if (locationId == null || !lockedPart.getCompany().getId().equals(companyId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return lockedPart.getLocations().stream().filter(location -> location.getId().equals(locationId)
				&& companyId.equals(location.getCompanyId())).findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/** Caller holds this part's row lock. Reallocation follows {@link #flushAndReallocate}. */
	public void changeLocked(Part part, PartLocation location, int delta) {
		if (location.getPart() != part) {
			throw new IllegalArgumentException("location belongs to another part");
		}
		int shelf = Math.addExact(location.getQuantity(), delta);
		int total = Math.addExact(part.getQuantity(), delta);
		if (shelf < 0 || total < 0) {
			throw new IllegalArgumentException("insufficient stock");
		}
		location.setQuantity(shelf);
		part.setQuantity(total);
		assertBalanced(part);
	}

	public void transferLocked(Part part, PartLocation source, PartLocation destination, int quantity) {
		if (quantity <= 0 || source == destination || source.getPart() != part || destination.getPart() != part) {
			throw new IllegalArgumentException("invalid transfer");
		}
		if (source.getQuantity() < quantity) {
			throw new IllegalArgumentException("insufficient stock");
		}
		source.setQuantity(source.getQuantity() - quantity);
		destination.setQuantity(Math.addExact(destination.getQuantity(), quantity));
		assertBalanced(part);
	}

	public void flushAndReallocate(Long companyId, Set<Long> partIds) {
		parts.flush();
		allocator.reallocateForParts(companyId, partIds);
	}

	public void assertBalanced(Part part) {
		long shelfTotal = part.getLocations().stream().mapToLong(PartLocation::getQuantity).sum();
		if (shelfTotal != part.getQuantity()) {
			throw new IllegalStateException("part total differs from location balances");
		}
	}

}

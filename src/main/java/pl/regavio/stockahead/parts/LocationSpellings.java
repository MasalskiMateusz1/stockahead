package pl.regavio.stockahead.parts;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-scoped resolver that gives every newly added location the one
 * spelling its shelf already has: the oldest spelling stored on any part,
 * otherwise the first spelling resolved earlier in this request, otherwise
 * the input as typed. Matching follows {@link PartLocation#sameLocation}.
 * Create one per write and use it inside that write's transaction.
 */
final class LocationSpellings {

	private final PartRepository partRepository;

	private final List<String> resolved = new ArrayList<>();

	LocationSpellings(PartRepository partRepository) {
		this.partRepository = partRepository;
	}

	/**
	 * Returns the canonical spelling for a normalized location. A spelling
	 * resolved earlier in this request already reflects the database, so it
	 * is reused without another query.
	 */
	String resolve(String location) {
		for (String known : resolved) {
			if (PartLocation.sameLocation(known, location)) {
				return known;
			}
		}
		String spelling = partRepository.findSpellingsOf(location).stream()
			.findFirst()
			.orElse(location);
		resolved.add(spelling);
		return spelling;
	}

}

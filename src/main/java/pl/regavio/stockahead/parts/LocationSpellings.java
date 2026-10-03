package pl.regavio.stockahead.parts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Request-scoped resolver that gives every newly added location the one
 * spelling its shelf already has: the oldest spelling stored on any part,
 * otherwise the first spelling resolved earlier in this request, otherwise
 * the input as typed. Matching follows {@link PartLocation#sameLocation}.
 * Obtain one per write through {@link #lockShelves} and use it inside that
 * write's transaction.
 */
final class LocationSpellings {

	/** First key of every shelf's {@code pg_advisory_xact_lock(int, int)}. */
	private static final int SHELF_LOCK_NAMESPACE = 0x5348454C;

	/**
	 * First key of every part name's {@code pg_advisory_xact_lock(int, int)};
	 * see {@link #lockPartNames}.
	 */
	private static final int PART_NAME_LOCK_NAMESPACE = 0x50415254;

	private final PartRepository partRepository;

	private final List<String> resolved = new ArrayList<>();

	private LocationSpellings(PartRepository partRepository) {
		this.partRepository = partRepository;
	}

	/**
	 * Takes a transaction-scoped lock on every shelf the write will resolve
	 * or re-spell, then returns the resolver. Two writers touching one shelf
	 * are serialized, and the second resolves only after the first commits,
	 * so concurrent writes can't store two spellings of the same shelf.
	 * <p>
	 * Call it first in the transaction, before any part row is locked or
	 * written: every writer takes shelf locks, sorted by key, before part
	 * locks, so writers never wait on each other in a cycle. Keys are
	 * hashes, so a collision only over-serializes two shelves.
	 */
	static LocationSpellings lockShelves(PartRepository partRepository, Collection<String> locations) {
		locations.stream()
			.map(location -> shelfKey(location).hashCode())
			.distinct()
			.sorted()
			.forEach(key -> partRepository.lockShelf(SHELF_LOCK_NAMESPACE, key));
		return new LocationSpellings(partRepository);
	}

	/**
	 * Takes a transaction-scoped lock on every part name the write may create,
	 * keyed by {@link PartsImportFile#nameKey} so case variants
	 * ({@code Rezystor 10k} and {@code rezystor 10K}) share a lock. The
	 * {@code parts.name} constraint is case-sensitive, so this is what keeps
	 * two writers from creating case variants of one part: the second waits
	 * until the first commits and then sees its part.
	 * <p>
	 * Lock order is shelves ({@link #lockShelves}) first, then part names,
	 * then part rows; within each step keys are sorted. Keys are hashes, so a
	 * collision only over-serializes two names.
	 */
	static void lockPartNames(PartRepository partRepository, Collection<String> names) {
		names.stream()
			.map(name -> PartsImportFile.nameKey(name).hashCode())
			.distinct()
			.sorted()
			.forEach(key -> partRepository.lockShelf(PART_NAME_LOCK_NAMESPACE, key));
	}

	/**
	 * Folds a location the way {@link String#equalsIgnoreCase} compares it
	 * (each code point upper-cased, then lower-cased), so two locations with
	 * {@link PartLocation#sameLocation} share a key.
	 */
	private static String shelfKey(String location) {
		return PartLocation.normalize(location).codePoints()
			.map(codePoint -> Character.toLowerCase(Character.toUpperCase(codePoint)))
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
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

package pl.regavio.stockahead.parts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts catalog's names indexed by {@link PartsImportFile#nameKey}, so a
 * file name matches a catalog part exactly when both names fold to the same
 * key: {@link PartLocation#normalize}d whitespace, letter case ignored per
 * code point. Matching happens in Java, not in SQL, so it never depends on
 * the database's {@code LC_CTYPE} (a C/POSIX {@code lower()} leaves
 * {@code Ł} unfolded), and a catalog name stored with a stray non-breaking
 * space still matches. A key shared by two catalog parts is ambiguous.
 */
final class CatalogNameIndex {

	/** One catalog part as stored: its id and its name. */
	record CatalogPart(Long id, String name) {
	}

	private final Map<String, List<CatalogPart>> partsByKey;

	private CatalogNameIndex(Map<String, List<CatalogPart>> partsByKey) {
		this.partsByKey = partsByKey;
	}

	/** Builds the index from {@link PartRepository#findAllIdsAndNames()} rows. */
	static CatalogNameIndex of(List<Object[]> idsAndNames) {
		Map<String, List<CatalogPart>> partsByKey = new HashMap<>();
		for (Object[] row : idsAndNames) {
			CatalogPart part = new CatalogPart((Long) row[0], (String) row[1]);
			partsByKey.computeIfAbsent(PartsImportFile.nameKey(part.name()), key -> new ArrayList<>()).add(part);
		}
		return new CatalogNameIndex(partsByKey);
	}

	/**
	 * Every catalog part whose name matches {@code name}: none for a new part,
	 * one for an existing part, more than one when the match is ambiguous.
	 */
	List<CatalogPart> matches(String name) {
		return List.copyOf(partsByKey.getOrDefault(PartsImportFile.nameKey(name), List.of()));
	}

}

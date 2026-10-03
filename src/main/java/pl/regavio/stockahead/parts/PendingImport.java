package pl.regavio.stockahead.parts;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import pl.regavio.stockahead.parts.PartsImportFile.ImportLine;

/**
 * The previewed import kept in the HTTP session under {@link #SESSION_ATTRIBUTE}
 * until it is accepted or cancelled, or replaced by the next upload. It holds
 * plain values only, never entities: the random {@code token} the confirm
 * form must send back, the merged file lines, and for each line (same index)
 * the catalog state the preview was built from.
 */
record PendingImport(String token, List<ImportLine> lines, List<Snapshot> snapshots) implements Serializable {

	static final String SESSION_ATTRIBUTE = "partsImport.pending";

	/**
	 * The catalog state one line was previewed against: the matched part's id
	 * ({@code null} for a new part) and that part's stock at preview time
	 * ({@code 0} for a new part).
	 */
	record Snapshot(Long partId, int quantityBefore) implements Serializable {
	}

	PendingImport {
		if (lines.size() != snapshots.size()) {
			throw new IllegalArgumentException("one snapshot per line expected");
		}
		lines = List.copyOf(lines);
		snapshots = List.copyOf(snapshots);
	}

	/** A pending import under a fresh random token. */
	static PendingImport create(List<ImportLine> lines, List<Snapshot> snapshots) {
		return new PendingImport(UUID.randomUUID().toString(), lines, snapshots);
	}

}

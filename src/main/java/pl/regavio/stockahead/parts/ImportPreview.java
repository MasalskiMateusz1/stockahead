package pl.regavio.stockahead.parts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import pl.regavio.stockahead.parts.CatalogNameIndex.CatalogPart;
import pl.regavio.stockahead.parts.PartsImportFile.ImportLine;
import pl.regavio.stockahead.parts.PendingImport.Snapshot;

/**
 * Resolves merged import lines against the parts catalog and computes the
 * resulting stock, without writing anything. Every call reads the catalog
 * afresh: the names through {@link PartRepository#findAllIdsAndNames()}
 * matched in Java by {@link CatalogNameIndex}, then the matched parts with
 * their locations.
 * <p>
 * Catalog-dependent errors, each reported on the line's first file row: a
 * name matching more than one catalog part (ambiguous), a new part with no
 * location on any of its rows, and an existing part whose stock after the
 * import would exceed {@link Integer#MAX_VALUE}. With any error the caller
 * rejects the whole file; otherwise the result carries one preview row and
 * one {@link Snapshot} per line, in line order.
 */
final class ImportPreview {

	/**
	 * One line of the preview. {@code locationsToAdd} are the file locations
	 * the part lacks under {@link PartLocation#sameLocation}, so for a new
	 * part all of them.
	 */
	record Row(String displayName, boolean newPart, boolean inactive, int quantityBefore, long quantityAdded,
			long quantityAfter, List<String> locationsToAdd) {

		Row {
			locationsToAdd = List.copyOf(locationsToAdd);
		}

	}

	/** New parts created, existing parts updated, and units added in total. */
	record Totals(int newParts, int updatedParts, long unitsAdded) {
	}

	/** Either catalog errors (and nothing else) or the rows, totals and snapshots. */
	record Result(List<ImportError> errors, List<Row> rows, Totals totals, List<Snapshot> snapshots) {

		Result {
			errors = List.copyOf(errors);
			rows = List.copyOf(rows);
			snapshots = List.copyOf(snapshots);
		}

		boolean hasErrors() {
			return !errors.isEmpty();
		}

	}

	private ImportPreview() {
	}

	static Result build(Collection<ImportLine> lines, PartRepository partRepository) {
		CatalogNameIndex index = CatalogNameIndex.of(partRepository.findAllIdsAndNames());

		List<List<CatalogPart>> matchesPerLine = new ArrayList<>(lines.size());
		Set<Long> matchedIds = new LinkedHashSet<>();
		for (ImportLine line : lines) {
			List<CatalogPart> matches = index.matches(line.name());
			matchesPerLine.add(matches);
			if (matches.size() == 1) {
				matchedIds.add(matches.get(0).id());
			}
		}
		Map<Long, Part> partsById = matchedIds.isEmpty() ? Map.of()
				: partRepository.findWithLocationsByIdIn(matchedIds)
					.stream()
					.collect(Collectors.toMap(Part::getId, Function.identity()));

		List<ImportError> errors = new ArrayList<>();
		List<Row> rows = new ArrayList<>(lines.size());
		List<Snapshot> snapshots = new ArrayList<>(lines.size());
		int newParts = 0;
		int updatedParts = 0;
		long unitsAdded = 0;
		int lineIndex = 0;
		for (ImportLine line : lines) {
			List<CatalogPart> matches = matchesPerLine.get(lineIndex++);
			int firstRow = line.rowNumbers().get(0);
			if (matches.size() > 1) {
				errors.add(ImportError.ofRow(firstRow, "partsImport.error.ambiguousName", line.name(),
						matches.stream().map(CatalogPart::name).sorted().collect(Collectors.joining(", "))));
				continue;
			}
			Part part = matches.isEmpty() ? null : partsById.get(matches.get(0).id());
			if (part == null) {
				if (line.locations().isEmpty()) {
					errors.add(ImportError.ofRow(firstRow, "partsImport.error.newPartLocationRequired", line.name()));
					continue;
				}
				rows.add(new Row(line.name(), true, false, 0, line.quantity(), line.quantity(), line.locations()));
				snapshots.add(new Snapshot(null, 0));
				newParts++;
				unitsAdded += line.quantity();
				continue;
			}

			long quantityAfter = (long) part.getQuantity() + line.quantity();
			if (quantityAfter > Integer.MAX_VALUE) {
				errors.add(ImportError.ofRow(firstRow, "partsImport.error.stockOverflow", part.getName(),
						Integer.MAX_VALUE));
				continue;
			}
			List<String> locationsToAdd = line.locations()
				.stream()
				.filter(location -> part.getLocations()
					.stream()
					.noneMatch(existing -> PartLocation.sameLocation(existing.getLocation(), location)))
				.toList();
			rows.add(new Row(part.getName(), false, !part.isActive(), part.getQuantity(), line.quantity(),
					quantityAfter, locationsToAdd));
			snapshots.add(new Snapshot(part.getId(), part.getQuantity()));
			updatedParts++;
			unitsAdded += line.quantity();
		}

		if (!errors.isEmpty()) {
			return new Result(errors, List.of(), null, List.of());
		}
		return new Result(List.of(), rows, new Totals(newParts, updatedParts, unitsAdded), snapshots);
	}

}

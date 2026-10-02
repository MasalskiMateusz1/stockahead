ALTER TABLE part_locations DROP CONSTRAINT part_locations_part_id_location_key;

UPDATE part_locations
SET location = canonical.location
FROM (
	SELECT DISTINCT ON (lower(location)) lower(location) AS location_key, location
	FROM part_locations
	ORDER BY lower(location), id
) canonical
WHERE lower(part_locations.location) = canonical.location_key
	AND part_locations.location <> canonical.location;

DELETE FROM part_locations duplicate
USING part_locations kept
WHERE kept.part_id = duplicate.part_id
	AND lower(kept.location) = lower(duplicate.location)
	AND kept.id < duplicate.id;

CREATE UNIQUE INDEX part_locations_part_location_ci_idx ON part_locations (part_id, lower(location));

-- The parts catalog belongs to a company. V14 guarantees parts and
-- part_locations are empty, so the columns are NOT NULL with no backfill.

ALTER TABLE parts ADD COLUMN company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT;

-- Part names are unique per company (FR-028). The leading company_id column
-- also serves lookups by company, so no separate parts_company_id_idx.
ALTER TABLE parts DROP CONSTRAINT parts_name_key;
ALTER TABLE parts ADD CONSTRAINT parts_company_name_key UNIQUE (company_id, name);

-- Target of the composite FK below: a location always carries its part's company.
ALTER TABLE parts ADD CONSTRAINT parts_id_company_key UNIQUE (id, company_id);

ALTER TABLE part_locations ADD COLUMN company_id BIGINT NOT NULL;
ALTER TABLE part_locations DROP CONSTRAINT part_locations_part_id_fkey;
ALTER TABLE part_locations ADD CONSTRAINT part_locations_part_company_fkey
	FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id) ON DELETE CASCADE;

-- part_locations_part_location_ci_idx (part_id, lower(location)) stays as is.
CREATE INDEX part_locations_company_location_idx ON part_locations (company_id, lower(location));

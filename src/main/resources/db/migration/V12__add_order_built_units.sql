ALTER TABLE orders ADD COLUMN built_units INT;

ALTER TABLE orders ADD CONSTRAINT orders_built_units_range_check
	CHECK (built_units IS NULL OR (built_units >= 0 AND built_units <= quantity_units));
ALTER TABLE orders ADD CONSTRAINT orders_built_units_requires_report_check
	CHECK (built_units IS NULL OR completion_reported_at IS NOT NULL);

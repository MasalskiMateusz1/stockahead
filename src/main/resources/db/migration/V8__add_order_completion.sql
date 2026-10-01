ALTER TABLE orders ADD COLUMN completion_reported_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN completion_reported_by BIGINT REFERENCES accounts (id) ON DELETE RESTRICT;
ALTER TABLE orders ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE orders ADD CONSTRAINT orders_completion_reported_requires_taken_check
	CHECK (completion_reported_at IS NULL OR taken_at IS NOT NULL);
ALTER TABLE orders ADD CONSTRAINT orders_completion_reporter_check
	CHECK ((completion_reported_at IS NULL) = (completion_reported_by IS NULL));
ALTER TABLE orders ADD CONSTRAINT orders_completed_requires_report_check
	CHECK (status <> 'COMPLETED' OR (completed_at IS NOT NULL AND completion_reported_at IS NOT NULL));

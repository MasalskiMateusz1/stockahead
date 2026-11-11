-- An order's completion reporter belongs to the order's company, so an order
-- and its reporter can never link two companies. Replaces V8's single-column FK.
-- The FK keeps the default MATCH SIMPLE: an unreported order
-- (completion_reported_by IS NULL) passes. ON DELETE matches V8; V8's CHECKs stay.
-- Target accounts_id_company_key comes from V17.
ALTER TABLE orders DROP CONSTRAINT orders_completion_reported_by_fkey;
ALTER TABLE orders ADD CONSTRAINT orders_completion_reporter_company_fkey
	FOREIGN KEY (completion_reported_by, company_id) REFERENCES accounts (id, company_id) ON DELETE RESTRICT;

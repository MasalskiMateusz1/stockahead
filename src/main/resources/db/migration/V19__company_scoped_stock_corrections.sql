-- A stock correction belongs to its part's company, and its part and author
-- belong to that company, so a correction can never link two companies.
-- Rows written since V14 are backfilled from their part. ON DELETE matches V11
-- (part: default NO ACTION); the author FK is RESTRICT like V17/V18. The
-- index stock_corrections_part_created_idx and V11's CHECKs stay.
-- Targets parts_id_company_key (V15) and accounts_id_company_key (V17).
ALTER TABLE stock_corrections ADD COLUMN company_id BIGINT;
UPDATE stock_corrections SET company_id = parts.company_id FROM parts WHERE parts.id = stock_corrections.part_id;
ALTER TABLE stock_corrections ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE stock_corrections ADD CONSTRAINT stock_corrections_company_id_fkey
	FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT;

ALTER TABLE stock_corrections DROP CONSTRAINT stock_corrections_part_id_fkey;
ALTER TABLE stock_corrections ADD CONSTRAINT stock_corrections_part_company_fkey
	FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id);

ALTER TABLE stock_corrections DROP CONSTRAINT stock_corrections_account_id_fkey;
ALTER TABLE stock_corrections ADD CONSTRAINT stock_corrections_author_company_fkey
	FOREIGN KEY (account_id, company_id) REFERENCES accounts (id, company_id) ON DELETE RESTRICT;

-- An order's canceller belongs to the order's company. Replaces V9's
-- single-column FK. Default MATCH SIMPLE: an uncancelled order
-- (cancelled_by IS NULL) passes, as in V18. V9's CHECKs stay.
ALTER TABLE orders DROP CONSTRAINT orders_cancelled_by_fkey;
ALTER TABLE orders ADD CONSTRAINT orders_canceller_company_fkey
	FOREIGN KEY (cancelled_by, company_id) REFERENCES accounts (id, company_id) ON DELETE RESTRICT;

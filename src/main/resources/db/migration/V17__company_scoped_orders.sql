-- Orders and their lines belong to a company. V14 guarantees orders and
-- order_lines are empty, so the columns are NOT NULL with no backfill.

-- Target of the assignee FK below: an order's assignee belongs to the order's company.
ALTER TABLE accounts ADD CONSTRAINT accounts_id_company_key UNIQUE (id, company_id);

-- An order's project and assignee belong to the order's company, so an order
-- can never link two companies. The assignee FK keeps the default MATCH SIMPLE:
-- an unassigned order (assignee_id IS NULL) passes. ON DELETE actions match V6/V13.
ALTER TABLE orders ADD COLUMN company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT;
ALTER TABLE orders DROP CONSTRAINT orders_project_id_fkey;
ALTER TABLE orders DROP CONSTRAINT orders_assignee_id_fkey;
ALTER TABLE orders ADD CONSTRAINT orders_project_company_fkey
	FOREIGN KEY (project_id, company_id) REFERENCES projects (id, company_id) ON DELETE RESTRICT;
ALTER TABLE orders ADD CONSTRAINT orders_assignee_company_fkey
	FOREIGN KEY (assignee_id, company_id) REFERENCES accounts (id, company_id) ON DELETE RESTRICT;

-- Target of the composite FK below: a line carries its order's company.
ALTER TABLE orders ADD CONSTRAINT orders_id_company_key UNIQUE (id, company_id);

-- Serves the order lists by company and status. orders_assignee_idx stays as is.
CREATE INDEX orders_company_status_idx ON orders (company_id, status);

-- A line's order and part belong to the line's company, so a line can never
-- link two companies. ON DELETE actions match V6.
ALTER TABLE order_lines ADD COLUMN company_id BIGINT NOT NULL;
ALTER TABLE order_lines DROP CONSTRAINT order_lines_order_id_fkey;
ALTER TABLE order_lines DROP CONSTRAINT order_lines_part_id_fkey;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_order_company_fkey
	FOREIGN KEY (order_id, company_id) REFERENCES orders (id, company_id) ON DELETE CASCADE;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_part_company_fkey
	FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id) ON DELETE RESTRICT;

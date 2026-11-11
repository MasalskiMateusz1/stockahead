DO $$
BEGIN
	IF EXISTS (SELECT 1 FROM accounts)
			OR EXISTS (SELECT 1 FROM parts)
			OR EXISTS (SELECT 1 FROM part_locations)
			OR EXISTS (SELECT 1 FROM projects)
			OR EXISTS (SELECT 1 FROM bom_lines)
			OR EXISTS (SELECT 1 FROM project_links)
			OR EXISTS (SELECT 1 FROM orders)
			OR EXISTS (SELECT 1 FROM order_lines)
			OR EXISTS (SELECT 1 FROM stock_corrections) THEN
		RAISE EXCEPTION 'V14: baza nie jest pusta — wyczyść wszystkie tabele aplikacji przed wdrożeniem';
	END IF;
END $$;

CREATE TABLE companies (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	name VARCHAR(255) NOT NULL CHECK (btrim(name) <> ''),
	status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'BLOCKED')),
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE accounts ADD COLUMN company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT;

CREATE INDEX accounts_company_id_idx ON accounts (company_id);

DROP INDEX accounts_single_manager_idx;

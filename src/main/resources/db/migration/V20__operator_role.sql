ALTER TABLE accounts DROP CONSTRAINT accounts_role_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_role_check CHECK (role IN ('OPERATOR', 'MANAGER', 'TECHNICIAN'));

ALTER TABLE accounts ALTER COLUMN company_id DROP NOT NULL;
ALTER TABLE accounts ADD CONSTRAINT accounts_operator_without_company_check
	CHECK ((role = 'OPERATOR') = (company_id IS NULL));

CREATE UNIQUE INDEX accounts_single_operator_idx ON accounts (role) WHERE role = 'OPERATOR';

ALTER TABLE accounts ADD COLUMN email_released_at TIMESTAMPTZ;

ALTER TABLE accounts DROP CONSTRAINT accounts_email_key;

DROP INDEX accounts_email_canonical_idx;
CREATE UNIQUE INDEX accounts_email_canonical_idx ON accounts (lower(btrim(email))) WHERE email_released_at IS NULL;

ALTER TABLE accounts ADD CONSTRAINT accounts_released_inactive_check
	CHECK (email_released_at IS NULL OR NOT active);
ALTER TABLE accounts ADD CONSTRAINT accounts_operator_not_released_check
	CHECK (email_released_at IS NULL OR role <> 'OPERATOR');

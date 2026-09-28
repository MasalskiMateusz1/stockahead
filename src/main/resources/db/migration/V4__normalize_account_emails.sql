DO $$
DECLARE
	collisions TEXT;
BEGIN
	SELECT string_agg(canonical_email, ', ' ORDER BY canonical_email)
	INTO collisions
	FROM (
		SELECT lower(btrim(email)) AS canonical_email
		FROM accounts
		GROUP BY lower(btrim(email))
		HAVING count(*) > 1
	) duplicate_emails;

	IF collisions IS NOT NULL THEN
		RAISE EXCEPTION 'V4: kolizja kanonicznych e-maili: %', collisions;
	END IF;
END $$;

UPDATE accounts SET email = lower(btrim(email));

CREATE UNIQUE INDEX accounts_email_canonical_idx ON accounts (lower(btrim(email)));

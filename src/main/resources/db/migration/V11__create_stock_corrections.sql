CREATE TABLE stock_corrections (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	part_id BIGINT NOT NULL REFERENCES parts (id),
	account_id BIGINT NOT NULL REFERENCES accounts (id),
	quantity_before INT NOT NULL CHECK (quantity_before >= 0),
	quantity_after INT NOT NULL CHECK (quantity_after >= 0),
	reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	CHECK (quantity_before <> quantity_after)
);

CREATE INDEX stock_corrections_part_created_idx ON stock_corrections (part_id, created_at DESC);

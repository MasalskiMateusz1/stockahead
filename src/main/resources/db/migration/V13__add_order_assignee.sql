ALTER TABLE orders ADD COLUMN assignee_id BIGINT REFERENCES accounts (id) ON DELETE RESTRICT;

CREATE INDEX orders_assignee_idx ON orders (assignee_id);

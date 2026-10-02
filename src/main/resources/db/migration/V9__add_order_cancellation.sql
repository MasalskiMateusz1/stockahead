ALTER TABLE orders ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN cancelled_by BIGINT REFERENCES accounts (id) ON DELETE RESTRICT;

ALTER TABLE orders ADD CONSTRAINT orders_cancelled_status_check
	CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));
ALTER TABLE orders ADD CONSTRAINT orders_canceller_check
	CHECK ((cancelled_at IS NULL) = (cancelled_by IS NULL));

ALTER TABLE order_lines ADD COLUMN returned_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_returned_quantity_check
	CHECK (returned_quantity >= 0 AND returned_quantity <= picked_quantity);

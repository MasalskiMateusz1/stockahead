ALTER TABLE orders ADD COLUMN taken_at TIMESTAMPTZ;

ALTER TABLE order_lines ADD COLUMN picked_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE order_lines DROP CONSTRAINT order_lines_check;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_quantities_check
	CHECK (picked_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity + picked_quantity <= required_quantity);

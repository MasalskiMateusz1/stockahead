-- Legacy totals have no trustworthy shelf distribution. Preserve catalog and
-- order history, but discard physical stock and live open-order reservations.
ALTER TABLE part_locations
	ADD COLUMN quantity INT NOT NULL DEFAULT 0 CHECK (quantity >= 0);

UPDATE parts SET quantity = 0;
UPDATE order_lines SET reserved_quantity = 0
WHERE order_id IN (SELECT id FROM orders WHERE status = 'OPEN');

ALTER TABLE stock_corrections ADD COLUMN location_name VARCHAR(255);

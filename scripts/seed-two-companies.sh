#!/usr/bin/env bash
# Seeds the throwaway database of `./mvnw spring-boot:test-run` with two companies
# for manual testing of company isolation (picking, orders, completion).
#
#   Firma A: manager-a@test.local, tech-a@test.local
#   Firma B: manager-b@test.local, tech-b@test.local
#   Password for every account: Test1234!
#
# Each company gets the same parts ("Rezystor 10k" x100, "Kondensator 100nF" x50),
# a project "Sterownik" (BOM: 2 resistors + 1 capacitor per unit), and open orders
# created through the real POST /orders route, so the allocator computes reservations:
#   Firma A: 5 units HIGH
#   Firma B: 3 units NORMAL, 2 units LOW
#
# Usage (app running on APP_URL, default http://localhost:8080):
#   ./scripts/seed-two-companies.sh
# Overrides: APP_URL, PG_CONTAINER (default: the newest running postgres container).
# Local test data only: never point this at a real database.
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
PASSWORD='Test1234!'
PG_CONTAINER="${PG_CONTAINER:-$(docker ps --filter ancestor=postgres:18 --format '{{.ID}}' | head -1)}"
if [ -z "$PG_CONTAINER" ]; then
	echo "No running postgres:18 container. Start ./mvnw spring-boot:test-run first, or set PG_CONTAINER." >&2
	exit 1
fi

psql_c() {
	# "sh" fills $0, so psql receives this function's arguments (e.g. -c "<query>") as "$@".
	# On Windows, docker exec output may end lines with \r; strip it so "0" compares as "0".
	docker exec -i "$PG_CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@" | tr -d '\r'
}

if [ "$(psql_c -c "select count(*) from companies where name in ('Firma A', 'Firma B')")" != "0" ]; then
	echo "Firma A / Firma B already exist in this database; nothing to do." >&2
	exit 1
fi

echo "Seeding master data into container $PG_CONTAINER..."
psql_c <<SQL
CREATE EXTENSION IF NOT EXISTS pgcrypto;
BEGIN;
INSERT INTO companies (name, status) VALUES ('Firma A', 'ACTIVE'), ('Firma B', 'ACTIVE');
INSERT INTO accounts (email, password_hash, role, company_id)
SELECT e.email, crypt('$PASSWORD', gen_salt('bf', 10)), e.role, c.id
FROM (VALUES ('manager-a@test.local', 'MANAGER', 'Firma A'), ('tech-a@test.local', 'TECHNICIAN', 'Firma A'),
		('manager-b@test.local', 'MANAGER', 'Firma B'), ('tech-b@test.local', 'TECHNICIAN', 'Firma B')) e(email, role, company)
JOIN companies c ON c.name = e.company;
INSERT INTO parts (name, quantity, company_id)
SELECT p.name, p.qty, c.id
FROM companies c CROSS JOIN (VALUES ('Rezystor 10k', 100), ('Kondensator 100nF', 50)) p(name, qty)
WHERE c.name IN ('Firma A', 'Firma B');
-- Each part has one shelf holding its whole stock, so parts.quantity = SUM(part_locations.quantity) (V22).
INSERT INTO part_locations (part_id, location, quantity, company_id)
SELECT p.id, CASE p.name WHEN 'Rezystor 10k' THEN 'Regał 1' ELSE 'Regał 2' END, p.quantity, p.company_id
FROM parts p JOIN companies c ON c.id = p.company_id WHERE c.name IN ('Firma A', 'Firma B');
INSERT INTO projects (name, company_id) SELECT 'Sterownik', id FROM companies WHERE name IN ('Firma A', 'Firma B');
INSERT INTO bom_lines (project_id, part_id, quantity_per_unit, company_id)
SELECT pr.id, pa.id, CASE pa.name WHEN 'Rezystor 10k' THEN 2 ELSE 1 END, pr.company_id
FROM projects pr JOIN parts pa ON pa.company_id = pr.company_id JOIN companies c ON c.id = pr.company_id
WHERE c.name IN ('Firma A', 'Firma B');
COMMIT;
SQL

JAR_DIR="$(mktemp -d)"
trap 'rm -rf "$JAR_DIR"' EXIT

csrf() {
	grep -o 'name="_csrf"[^>]*value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//'
}

# create_order <company> <manager email> <units> <priority>
create_order() {
	local jar="$JAR_DIR/$2.txt" token project_id
	project_id="$(psql_c -c "select p.id from projects p join companies c on c.id = p.company_id where c.name = '$1' and p.name = 'Sterownik'")"
	token="$(curl -sf -c "$jar" -b "$jar" "$APP_URL/login" | csrf)"
	curl -sf -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$2" --data-urlencode "password=$PASSWORD" \
		--data-urlencode "_csrf=$token" "$APP_URL/login"
	token="$(curl -sf -c "$jar" -b "$jar" "$APP_URL/orders/new" | csrf)"
	curl -sf -o /dev/null -w "$1: order $3 x $4 -> %{redirect_url}\n" -c "$jar" -b "$jar" \
		-d "projectId=$project_id&quantityUnits=$3&priority=$4&requiredDate=$(date -d '+45 days' +%F)&assigneeId=&_csrf=$token" \
		"$APP_URL/orders"
}

echo "Creating orders through $APP_URL/orders..."
create_order 'Firma A' manager-a@test.local 5 HIGH
create_order 'Firma B' manager-b@test.local 3 NORMAL
create_order 'Firma B' manager-b@test.local 2 LOW

echo
psql_c -c "select c.name || ' | order ' || o.id || ' | ' || p.name || ' | reserved ' || ol.reserved_quantity
	from orders o join companies c on c.id = o.company_id join order_lines ol on ol.order_id = o.id
	join parts p on p.id = ol.part_id where c.name in ('Firma A', 'Firma B') order by o.id, ol.id"
echo
echo "Done. Log in with any of the accounts above, password: $PASSWORD"

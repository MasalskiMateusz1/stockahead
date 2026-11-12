#!/usr/bin/env bash
# Starts Stockahead against a throwaway Postgres (./mvnw spring-boot:test-run) and seeds demo data
# through the real forms, so a manual UI check can start straight away.
#
#   scripts/manual-test.sh            start the app, seed it, keep it running (Ctrl+C stops it)
#   scripts/manual-test.sh --no-seed  start the app with an empty database
#   scripts/manual-test.sh --seed-only seed an app that is already running on $BASE_URL
#
# Needs Docker running, curl and bash. The database lives only as long as the app.
# Env: PORT (default 8080), STOCKAHEAD_SETUP_TOKEN (default matches application.properties).

set -euo pipefail

cd "$(dirname "$0")/.."

PORT="${PORT:-8080}"
BASE_URL="${BASE_URL:-http://localhost:$PORT}"
SETUP_TOKEN="${STOCKAHEAD_SETUP_TOKEN:-local-only-setup-token}"
PASSWORD="haslo-testowe-123"
LOG_FILE="target/manual-test-app.log"
WORK_DIR="$(mktemp -d)"
APP_PID=""

OPERATOR_EMAIL="operator@stockahead.test"
MANAGER_EMAIL="kierownik@regavio.test"
TECH_EMAIL="technik@regavio.test"
TECH2_EMAIL="technik2@regavio.test"
EMPTY_MANAGER_EMAIL="kierownik@pusta.test"
PENDING_MANAGER_EMAIL="kierownik@oczekujaca.test"

MODE="full"
case "${1:-}" in
	--no-seed) MODE="no-seed" ;;
	--seed-only) MODE="seed-only" ;;
	"") ;;
	-h|--help) sed -n '2,10p' "$0"; exit 0 ;;
	*) echo "Unknown option: $1" >&2; exit 2 ;;
esac

cleanup() {
	rm -rf "$WORK_DIR"
	if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
		echo
		echo "Stopping the app..."
		kill "$APP_PID" 2>/dev/null || true
		wait "$APP_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- HTTP helpers (one cookie jar per user) ----------

jar() { echo "$WORK_DIR/$1.cookies"; }

# get <user> <path>  -> page body on stdout
get() {
	curl -sS -b "$(jar "$1")" -c "$(jar "$1")" "$BASE_URL$2"
}

csrf_from() { grep -oP 'name="_csrf"[^>]*value="\K[^"]+' | head -n1; }

# post <user> <form-page-path> <action-path> [curl --data-urlencode args...]
# Loads the form page for a fresh CSRF token, posts, and prints the redirect Location (or the status code).
post() {
	local user="$1" page="$2" action="$3"
	shift 3
	local token
	token="$(get "$user" "$page" | csrf_from)"
	[[ -n "$token" ]] || fail "no CSRF token on $page for $user"
	post_with_token "$user" "$token" "$action" "$@"
}

# post_with_token <user> <csrf token> <action-path> [field=value...]
post_with_token() {
	local user="$1" token="$2" action="$3"
	shift 3
	local args=()
	for kv in "$@"; do args+=(--data-urlencode "$kv"); done
	curl -sS -o "$WORK_DIR/last-body.html" -w '%{http_code} %{redirect_url}' \
		-b "$(jar "$user")" -c "$(jar "$user")" \
		--data-urlencode "_csrf=$token" "${args[@]}" "$BASE_URL$action"
}

# expect_redirect <result-of-post> <description>: a successful form post redirects (302/303)
expect_redirect() {
	local code="${1%% *}"
	if [[ "$code" != 302 && "$code" != 303 ]]; then
		grep -oP 'class="alert[^"]*"[^>]*>\K[^<]+' "$WORK_DIR/last-body.html" | head -n3 >&2 || true
		fail "$2 failed (HTTP $code)"
	fi
}

login() {
	local user="$1" email="$2"
	rm -f "$(jar "$user")"
	local result
	result="$(post "$user" /login /login "username=$email" "password=$PASSWORD")"
	[[ "$result" != *"error"* && "$result" != *"blocked"* ]] || fail "login as $email failed ($result)"
}

register_company() {
	local user="$1" company="$2" email="$3"
	rm -f "$(jar "$user")"
	# Each render of /register restarts the bot-check timer, so post with the token from this one load.
	local token
	token="$(get "$user" /register | csrf_from)"
	sleep 3.2   # the bot check rejects a form sent sooner than app.registration.min-fill-time
	expect_redirect "$(post_with_token "$user" "$token" /register \
		"companyName=$company" "email=$email" "password=$PASSWORD" "confirmPassword=$PASSWORD" "hp_7c1f=")" \
		"registering $company"
}

# option_id <html-file> <option-text> -> value of <option value="N">text</option>
option_id() {
	{ grep -oP "<option value=\"\K[0-9]+(?=\"[^>]*>\Q$2\E</option>)" "$1" || true; } | head -n1
}

future_date() { date -d "+$1 days" +%F; }

# ---------- seeding ----------

seed() {
	seed_accounts
	seed_warehouse
}

# seed_accounts: operator, the three companies, approvals and Regavio's technicians.
seed_accounts() {
	log "Creating the operator account"
	expect_redirect "$(post op /setup /setup \
		"email=$OPERATOR_EMAIL" "password=$PASSWORD" "confirmPassword=$PASSWORD" "token=$SETUP_TOKEN")" "operator setup"

	log "Registering companies"
	register_company regA "Regavio Sp. z o.o." "$MANAGER_EMAIL"
	register_company regB "Pusta firma" "$EMPTY_MANAGER_EMAIL"
	register_company regC "Oczekująca firma" "$PENDING_MANAGER_EMAIL"

	log "Approving Regavio and Pusta firma as the operator"
	login op "$OPERATOR_EMAIL"
	local companies
	companies="$(get op /operator/companies)"
	for name in "Regavio Sp. z o.o." "Pusta firma"; do
		local id
		id="$(grep -A40 -F "$name" <<<"$companies" | grep -oP '/operator/companies/\K[0-9]+(?=/approve)' | head -n1 || true)"
		[[ -n "$id" ]] || fail "no approve button for $name"
		expect_redirect "$(post op /operator/companies "/operator/companies/$id/approve")" "approving $name"
	done

	log "Manager: technicians"
	login mgr "$MANAGER_EMAIL"
	for email in "$TECH_EMAIL" "$TECH2_EMAIL"; do
		expect_redirect "$(post mgr /manager/accounts/new /manager/accounts \
			"email=$email" "password=$PASSWORD" "confirmPassword=$PASSWORD" "role=TECHNICIAN")" "creating $email"
	done

}

# seed_warehouse: parts, projects, orders and one reported order for Regavio. Runs on its own when a
# previous run created the accounts but stopped before the data (see the seed-only branch below).
seed_warehouse() {
	login mgr "$MANAGER_EMAIL"
	log "Manager: parts"
	local part
	while IFS='|' read -r name qty locs; do
		expect_redirect "$(post mgr /parts/new /parts "name=$name" "quantity=$qty" "locations=$locs")" "creating part $name"
	done <<'EOF'
Rezystor 10k 0603|500|A-01-01
Kondensator 100nF 0603|300|A-01-02
Mikrokontroler STM32F103|12|B-02-01
Złącze USB-C|40|B-02-03
Dioda LED czerwona|200|A-03-01
Stabilizator LM1117 3.3V|6|C-01-01
Przekaźnik 5V|0|C-02-04
EOF

	log "Manager: projects and BOMs"
	local proj_loc proj_id page="$WORK_DIR/project.html"
	create_project() {
		proj_loc="$(post mgr /projects/new /projects "name=$1")"
		expect_redirect "$proj_loc" "creating project $1"
		proj_id="$(grep -oP '/projects/\K[0-9]+$' <<<"$proj_loc")"
		[[ -n "$proj_id" ]] || fail "no id for project $1 ($proj_loc)"
	}
	add_bom() {   # add_bom <part name> <qty per unit>
		get mgr "/projects/$proj_id" >"$page"
		local pid
		pid="$(option_id "$page" "$1")"
		[[ -n "$pid" ]] || fail "part $1 not offered on project $proj_id"
		expect_redirect "$(post mgr "/projects/$proj_id" "/projects/$proj_id/bom" "partId=$pid" "quantityPerUnit=$2")" "BOM line $1"
	}

	create_project "Sterownik bramy"
	local p_gate="$proj_id"
	add_bom "Mikrokontroler STM32F103" 1
	add_bom "Rezystor 10k 0603" 8
	add_bom "Kondensator 100nF 0603" 6
	add_bom "Stabilizator LM1117 3.3V" 1

	create_project "Moduł przekaźników"
	local p_relay="$proj_id"
	add_bom "Przekaźnik 5V" 4
	add_bom "Dioda LED czerwona" 4
	add_bom "Rezystor 10k 0603" 4

	create_project "Ładowarka USB-C"
	local p_usb="$proj_id"
	add_bom "Złącze USB-C" 1
	add_bom "Kondensator 100nF 0603" 4
	add_bom "Dioda LED czerwona" 1

	create_project "Prototyp (bez BOM)"

	log "Manager: orders"
	get mgr /orders/new >"$page"
	local tech_id tech2_id
	tech_id="$(option_id "$page" "$TECH_EMAIL")"
	tech2_id="$(option_id "$page" "$TECH2_EMAIL")"
	[[ -n "$tech_id" && -n "$tech2_id" ]] || fail "technicians not offered on /orders/new"
	order() {   # order <project id> <units> <priority> <days ahead> <assignee id or empty>
		expect_redirect "$(post mgr /orders/new /orders "projectId=$1" "quantityUnits=$2" "priority=$3" \
			"requiredDate=$(future_date "$4")" "assigneeId=$5")" "creating order for project $1"
	}
	order "$p_gate" 2 HIGH 3 "$tech_id"
	order "$p_usb" 5 NORMAL 7 "$tech_id"
	order "$p_relay" 3 NORMAL 10 "$tech_id"     # relays are out of stock -> shopping list
	order "$p_gate" 10 LOW 21 "$tech2_id"       # needs more MCUs and regulators than in stock
	order "$p_usb" 2 LOW 14 ""                  # unassigned

	log "Technician: pick one order completely and report it"
	login tech "$TECH_EMAIL"
	local first_order
	first_order="$(get tech /picking | grep -oP 'href="/picking/\K[0-9]+' | head -n1 || true)"
	[[ -n "$first_order" ]] || fail "technician sees no orders on /picking"
	get tech "/picking/$first_order" | tr "\n\t" "  " >"$page"
	local line
	for line in $(grep -oP "/picking/$first_order/lines/\K[0-9]+(?=/pick)" "$page" | sort -u); do
		local qty
		qty="$(grep -oP "id=\"quantity-$line\"[^>]*value=\"\K[0-9]+" "$page" | head -n1 || true)"
		[[ -n "$qty" && "$qty" != 0 ]] || continue
		expect_redirect "$(post tech "/picking/$first_order" "/picking/$first_order/lines/$line/pick" "quantity=$qty")" "picking line $line"
	done
	get tech "/picking/$first_order" | tr "\n\t" "  " >"$page"
	local built
	built="$(grep -oP 'id="builtUnits"[^>]*value="\K[0-9]+' "$page" | head -n1 || true)"
	expect_redirect "$(post tech "/picking/$first_order" "/picking/$first_order/report-completion" "builtUnits=${built:-1}")" \
		"reporting completion of order $first_order"
}

print_accounts() {
	cat <<EOF

  App:       $BASE_URL$( [[ -n "$APP_PID" ]] && echo "        (log: $LOG_FILE)")
  Password:  $PASSWORD   (every account)

  Operator              $OPERATOR_EMAIL
  Manager  (Regavio)    $MANAGER_EMAIL       parts, projects, 5 orders (1 awaiting confirmation), shortages
  Technician (Regavio)  $TECH_EMAIL         3 assigned orders, one reported
  Technician (Regavio)  $TECH2_EMAIL
  Manager  (Pusta)      $EMPTY_MANAGER_EMAIL         approved company with no data -> empty states
  Manager  (pending)    $PENDING_MANAGER_EMAIL   not approved -> /awaiting-approval

EOF
}

wait_for_app() {
	log "Waiting for $BASE_URL/actuator/health (first start pulls postgres:18 and compiles)..."
	for _ in $(seq 1 180); do
		if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
			return 0
		fi
		if [[ -n "$APP_PID" ]] && ! kill -0 "$APP_PID" 2>/dev/null; then
			tail -n 40 "$LOG_FILE" >&2
			fail "the app exited during startup; see $LOG_FILE"
		fi
		sleep 2
	done
	fail "the app did not become healthy within 6 minutes; see $LOG_FILE"
}

# ---------- main ----------

# seed_state: "none" (no demo manager yet), "accounts" (accounts exist but no parts, e.g. an
# interrupted run) or "full".
seed_state() {
	rm -f "$(jar probe)"
	local result
	result="$(post probe /login /login "username=$MANAGER_EMAIL" "password=$PASSWORD")"
	if [[ "$result" == *"error"* ]]; then
		echo none
	elif get probe /parts | grep -qF "Rezystor 10k 0603"; then
		echo full
	else
		echo accounts
	fi
}

if [[ "$MODE" == "seed-only" ]]; then
	curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1 || fail "the app is not running on $BASE_URL; start it first (or run scripts/manual-test.sh)"
	case "$(seed_state)" in
		full) log "Demo data is already there; nothing to add." ;;
		accounts) log "Accounts exist but no warehouse data (an earlier run stopped); adding the rest"; seed_warehouse ;;
		*) seed ;;
	esac
	print_accounts
	exit 0
fi

command -v docker >/dev/null && docker info >/dev/null 2>&1 || fail "Docker is not running"
if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
	fail "the app is already running on $BASE_URL; to add demo data to it run scripts/seed-data.sh"
fi

mkdir -p target
log "Starting the app on port $PORT (./mvnw spring-boot:test-run)"
./mvnw -q spring-boot:test-run -Dspring-boot.run.arguments="--server.port=$PORT" >"$LOG_FILE" 2>&1 &
APP_PID=$!

wait_for_app
[[ "$MODE" == "full" ]] && seed
print_accounts
log "Running. Press Ctrl+C to stop (the database is thrown away)."
wait "$APP_PID"

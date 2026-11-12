#!/usr/bin/env bash
# Adds the demo data (companies, accounts, parts, projects, orders) to a Stockahead app that is
# already running. Safe to run twice: if the demo data is already there it only lists the accounts.
#
#   scripts/seed-data.sh               app on http://localhost:8080
#   PORT=8081 scripts/seed-data.sh     app on another port
#
# The seeding itself lives in manual-test.sh (its --seed-only mode), so there is one copy of it.
exec "$(dirname "$0")/manual-test.sh" --seed-only

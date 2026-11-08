<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Registration Bot Protection

- **Plan**: context/changes/registration-bot-protection/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-11-08
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Notes:
- `./mvnw verify` exit 0: 725 tests, 0 failures (RegistrationIntegrationTests 24, RegistrationRateLimiterTests 8).
- All planned tests exist. One extra unit test (`releasingTheSameReservationTwiceFreesOnlyOneSlot`) is harmless.
- Manual check 2.6 (real client IP in the prod logs) is still pending. It is blocked on deploy, which runs only from main. Phase 2 is reviewed on its code and automated checks.

## Findings

### F1 — Honeypot field name invites browser autofill

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/RegistrationBotCheck.java:26, src/main/resources/templates/register.html:33
- **Detail**: The plan suggested the name `company_website_url`, and the code uses it. It contains "company", "website" and "url", which are tokens that Chrome's and other browsers' autofill heuristics map to the organization and URL fields of an address profile. Chrome ignores `autocomplete="off"` for address autofill. A real user with a saved profile who autofills the email field could get the hidden field filled too. That user is then rejected with the neutral error on every attempt, because each autofill fills the field again. This is a false positive the plan explicitly wanted to avoid ("a human completes sign-up without noticeable friction"). Plausible, not reproduced.
- **Fix**: Rename the parameter to a token with no autofill keywords (e.g. `hp_7c1f`). Keep the label neutral, and keep `tabindex=-1` and `aria-hidden`.
  - Strength: Removes the heuristic match. The constant is referenced from one place (the template uses the literal, test uses the constant).
  - Tradeoff: Some naive bots fill only fields with plausible names. Bots that fill every field still trip it.
  - Confidence: MED — autofill heuristics are browser-specific and change over time.
  - Blind spot: Not tested in a real Chrome with a saved address profile.
- **Decision**: FIXED — renamed to `hp_7c1f` in the constant and the template

### F2 — Per-IP cap is keyed on the full IPv6 address

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/ClientAddress.java:22
- **Detail**: Cloudflare serves IPv6 by default, and `CF-Connecting-IP` then carries a full /128 address. An attacker with a single /64 (standard for one home or VPS) can rotate through practically unlimited addresses. That bypasses the 5-per-hour cap entirely, which is the backstop for bots that beat the honeypot and the timer. IPv4 keys are fine.
- **Fix**: Normalize the key in `ClientAddress.of`: IPv4 stays as is, and IPv6 is reduced to its /64 prefix. Add a test where two addresses in the same /64 share a quota.
  - Strength: Closes the cheapest bypass. A small change in one helper.
  - Tradeoff: Users sharing a /64 (rare: one household or one office) share 5 registrations an hour, which is acceptable for company sign-ups.
  - Confidence: HIGH — /64 bucketing is standard practice for IPv6 rate limits.
  - Blind spot: Whether test.regavio.com has IPv6 enabled on the Cloudflare zone has not been checked.
- **Decision**: FIXED — `ClientAddress.rateLimitKey` (IPv6 → /64, IPv4-mapped → IPv4) used as the limiter key; log keeps full address; `ClientAddressTests` + `ipv6AddressesInOneSlash64ShareQuota`

### F3 — Form timestamp survives a successful registration

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/RegistrationController.java:157
- **Detail**: After a successful POST the session keeps its old issued-at stamp. A script can do one GET, wait 3 s, and then POST repeatedly with the same session and CSRF token, without waiting again. The rate limit still caps it at 5 per hour, so the impact is small, but the timer only works once per session.
- **Fix**: Call `session.removeAttribute(FORM_ISSUED_AT_SESSION_ATTRIBUTE)` before the success redirect, so each registration needs a fresh form render.
- **Decision**: FIXED — `RegistrationBotCheck.clear` called before the success redirect; test `sessionCannotBeReusedForASecondRegistration`

## Triage summary

- Fixed: F1, F2, F3 (3)
- `./mvnw verify` after fixes: exit 0, 732 tests, 0 failures

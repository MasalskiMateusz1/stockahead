# Registration Bot Protection Implementation Plan

## Overview

Roadmap S-10. Make `/register` refuse automated sign-ups before they create a PENDING company (FR-023 human-verification resolution; prd-v2 NFR "Automated mass registration is rejected before it reaches the operator's pending list, while a human completes sign-up without noticeable friction"). The mechanism is self-hosted, with no third-party service: a honeypot field, a minimum fill time stamped in the HTTP session, and an in-memory per-IP cap on successful registrations.

## Current State Analysis

- `account/RegistrationController.java:60-104` validates in this order: company name, then credentials (`AccountCredentials`). It then creates the company and the manager in one `TransactionTemplate` and maps `DataIntegrityViolationException` to the duplicate-email error. It has no bot check of any kind.
- `templates/register.html` is a plain form with four fields and the CSRF token Thymeleaf adds automatically. No JavaScript is required.
- `security/SecurityConfig.java:47` permits `/register`. CSRF stays on, so a POST already needs a session cookie plus a token taken from a previous GET.
- Prod traffic reaches the app only through Cloudflare Tunnel → Traefik → app (`context/foundation/deployment.md` hostnames table; "nothing answers on the public IP"). Cloudflare sets `CF-Connecting-IP` to the real client address. `server.forward-headers-strategy=framework` resolves `remoteAddr` from the leftmost `X-Forwarded-For`, which the client can spoof.
- The app has no `Clock` bean and no rate-limiting code.
- `RegistrationIntegrationTests` (14 tests) builds every POST through a single helper, `postRegister(...)` (lines ~91-98). All requests come from the MockMvc default address `127.0.0.1`, and most of them register successfully.

## Desired End State

- A POST to `/register` whose honeypot field is filled, or that has no form-issued timestamp in the session, or that arrives less than the minimum fill time after the form was issued, creates nothing. It re-renders the form with the company name and email the user entered, the neutral error "Nie udało się wysłać formularza. Spróbuj ponownie." and a fresh timestamp, and logs the event at INFO without naming the user.
- Once an IP address has completed 5 successful registrations within a sliding hour, its next attempt that passes validation creates nothing. The form re-renders with HTTP 429 and an explicit "too many registrations from this address" message.
- A person filling the form normally sees no difference from today.
- The checks are always on. There is no flag to switch them off.

### Key Discoveries:

- Validation errors re-render through `renderError(...)` (`RegistrationController.java:106-112`). Both new rejections reuse it so the form keeps what the user typed.
- The duplicate-email path rolls back the whole transaction (`RegistrationController.java:98-102`). That is also where the rate-limit slot has to be released.
- Config follows `app.*` properties with `@Value` (`SetupController.java:51`, `application.properties` `app.setup-token`).
- `[hidden]` is kept effective in `static/css/app.css:114`. The honeypot needs its own off-screen class instead, because some bots skip `hidden`/`display:none` inputs.
- Lesson "Handle DB constraint violations at write boundaries" is already honoured by the controller. The new checks must not move the save outside the existing `try`.

## What We're NOT Doing

- No Cloudflare Turnstile, reCAPTCHA, hCaptcha or any other external verification service. The user chose self-hosted only, so failing open or closed when a vendor is down doesn't apply.
- No enable/disable flag for the checks.
- No Cloudflare WAF rule and no edge configuration.
- No persistence of rate-limit state. Counts reset on restart, and each instance keeps its own counts during a rolling deploy.
- No email verification or other email features (roadmap Open Question 2 stays open).
- No change to `/login`, `/setup` or the technician form.
- No "fake success" response for bots.

## Implementation Approach

Every check runs before anything is written to the database, cheapest first: honeypot → timer → existing field validation → rate-limit reservation → transaction. The rate limit comes after field validation so that typos and wrong passwords don't use up an IP's quota. A slot is reserved before the transaction and released if the transaction fails, so two concurrent submissions from one IP can't both slip under the cap. The timer lives in the session rather than in a signed token, because CSRF already requires the session, so no signing key or extra secret is needed.

## Critical Implementation Details

- **Timing & lifecycle**: the issued-at timestamp must be stamped on *every* render of the form. That covers the GET and every `renderError` path, including the two new rejections. Otherwise a user who corrects a typo would be stuck with a stale or missing timestamp. Overwriting the timestamp on re-render is fine, because a human correcting a field always takes longer than the minimum fill time.
- **State sequencing**: reserve the rate-limit slot as the last step before the transaction, and release it in *every* path where the transaction does not commit. That includes the `DataIntegrityViolationException` catch and any unexpected runtime exception (use `try/finally` around the commit flag, not just the catch).
- **IP trust**: take `CF-Connecting-IP` when it is present and fall back to `request.getRemoteAddr()`. The header can be trusted only because prod has no route that bypasses Cloudflare. That assumption goes into the class Javadoc.

## Phase 1: Honeypot and minimum fill time

### Overview

Add the two self-hosted human checks to `/register` and adapt the existing tests to send a session that passes them.

### Changes Required:

#### 1. Registration bot check

**File**: `src/main/java/pl/regavio/stockahead/account/RegistrationBotCheck.java` (new)

**Intent**: Hold the honeypot and timer rules in one small component, so the controller only asks "stamp the form" and "does this submission pass".

**Contract**: A Spring `@Component` with a public constant `FORM_ISSUED_AT_SESSION_ATTRIBUTE` (session attribute holding an `Instant`) and a public constant `HONEYPOT_PARAMETER` (an unusual field name that browsers won't autofill, e.g. `company_website_url`). It provides `stamp(HttpSession)`, which sets the attribute to now, and `passes(HttpSession, String honeypotValue)`, which is true only when the honeypot is null or empty, the attribute is present, and at least the minimum fill time has passed since it was set. The minimum comes from `app.registration.min-fill-time` (a `Duration`, default `3s`).

#### 2. Controller wiring

**File**: `src/main/java/pl/regavio/stockahead/account/RegistrationController.java`

**Intent**: Stamp the session on GET and on every error re-render. On POST, run the bot check before the company-name validation. If it fails, log at INFO (no email or password in the log line) and re-render with `register.error.verificationFailed`.

**Contract**: `registerCompany` accepts the honeypot as an optional `@RequestParam(name = HONEYPOT_PARAMETER, required = false)` and an `HttpSession`. `showRegistrationForm` accepts `HttpSession`. `renderError` stamps the session. The redirect on success is unchanged. Update the class Javadoc to describe the check order.

#### 3. Template and styling

**File**: `src/main/resources/templates/register.html`, `src/main/resources/static/css/app.css`

**Intent**: Add the honeypot input so humans never see or reach it while naive form-fillers do.

**Contract**: An `<input type="text">` named `HONEYPOT_PARAMETER`, with `tabindex="-1"`, `autocomplete="off"` and `aria-hidden="true"`, inside a wrapper with a new off-screen class (absolutely positioned outside the viewport, not `display:none`). The label is neutral and is hidden along with the wrapper.

#### 4. Message

**File**: `src/main/resources/messages.properties`

**Intent**: Add the neutral error text.

**Contract**: `register.error.verificationFailed=Nie udało się wysłać formularza. Spróbuj ponownie.`

#### 5. Tests

**File**: `src/test/java/pl/regavio/stockahead/account/RegistrationIntegrationTests.java`

**Intent**: Keep the 14 existing tests green by having `postRegister` send a `MockHttpSession` whose issued-at time is 10 s in the past. Add rejection cases.

**Contract**: The helper sets `FORM_ISSUED_AT_SESSION_ATTRIBUTE` directly. New tests:
- `honeypotFilledCreatesNothingAndShowsNeutralError`
- `submissionWithoutFormTimestampIsRejected`
- `submissionFasterThanMinimumFillTimeIsRejected` (attribute set to now)
- `rejectedSubmissionKeepsEnteredNameAndEmail`
- `getRegisterStampsSessionTimestamp`
- `formRendersHoneypotFieldOffScreen`

Each rejection test asserts that no company row with the test prefix exists.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- New honeypot, missing-timestamp and too-fast tests in `RegistrationIntegrationTests` pass and assert no company row was created
- Existing `RegistrationIntegrationTests` cases pass unchanged in intent through the stamped-session helper

#### Manual Verification:

- On `./mvnw spring-boot:test-run`, a person filling `/register` normally registers successfully with no visible extra field or delay
- Submitting the form within ~1 s of loading it shows the neutral error with name and email kept (e.g. browser autofill and an immediate click, or `curl` with the session cookie and CSRF token)
- Tab order on `/register` skips the honeypot, and a screen reader does not announce it

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Per-IP registration rate limit

### Overview

Cap successful registrations per client IP in memory, so a script that beats the honeypot and timer still cannot flood the pending list.

### Changes Required:

#### 1. Rate limiter

**File**: `src/main/java/pl/regavio/stockahead/account/RegistrationRateLimiter.java` (new)

**Intent**: A thread-safe sliding-window counter of successful registrations per IP, with reserve/release semantics so that concurrent requests from one IP cannot exceed the cap.

**Contract**: A Spring `@Component`. Its limit comes from `app.registration.max-per-ip` (default `5`) and its window from `app.registration.rate-window` (default `1h`). The public constructor uses `Clock.systemUTC()`, and a package-private constructor takes a `Clock` for unit tests. `tryReserve(String ip)` returns a reservation handle, or empty when the IP already has `max` reservations within the window. `release(handle)` removes the reservation. Entries older than the window are pruned on every call, and an IP whose list becomes empty is removed from the map, so memory stays bounded by the IPs active within the last hour.

#### 2. Client IP resolution

**File**: `src/main/java/pl/regavio/stockahead/account/ClientAddress.java` (new; small static helper)

**Intent**: One place that decides which address a request comes from.

**Contract**: `static String of(HttpServletRequest)` returns the stripped `CF-Connecting-IP` header when it is non-blank and `getRemoteAddr()` otherwise. The Javadoc states that this relies on prod being reachable only through Cloudflare Tunnel.

#### 3. Controller wiring

**File**: `src/main/java/pl/regavio/stockahead/account/RegistrationController.java`, `src/main/resources/messages.properties`

**Intent**: After credential validation and just before the transaction, reserve a slot for the client IP. If none is free, log at INFO and re-render the form (stamped, name and email kept) with HTTP 429 and `register.error.tooManyRegistrations`. Release the slot when the transaction does not commit.

**Contract**: `registerCompany` takes `HttpServletRequest` (for the IP) and `HttpServletResponse` (to set status 429 while still returning the `register` view). The message reads `register.error.tooManyRegistrations=Zbyt wiele rejestracji z tego adresu. Spróbuj ponownie później.` The duplicate-email and unexpected-exception paths release the slot.

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/account/RegistrationRateLimiterTests.java` (new, plain JUnit with a mutable test `Clock`), `src/test/java/pl/regavio/stockahead/account/RegistrationIntegrationTests.java`

**Intent**: Prove the window, the reserve/release logic and the end-to-end 429. Keep the existing tests independent of the shared limiter, so they don't trip the cap and share one Spring context.

**Contract**:
- Unit tests:
  - `allowsUpToLimitWithinWindow`
  - `rejectsOverLimit`
  - `releasedReservationFreesSlot`
  - `slotFreesAfterWindowSlides`
  - `differentIpsAreIndependent`
  - `emptyIpEntriesArePruned`
  - `concurrentReservesNeverExceedLimit` (many threads, one IP)
- Integration changes:
  - The `postRegister` helper sends a unique `CF-Connecting-IP` per call.
  - New `sixthSuccessfulRegistrationFromSameIpIsRejectedWith429` uses a dedicated IP and the default limit of 5, and asserts there are exactly 5 companies.
  - New `failedValidationDoesNotConsumeQuota`.
  - New `duplicateEmailReleasesSlot`.
  - New `cfConnectingIpTakesPrecedenceOverRemoteAddr`.

### Success Criteria:

#### Automated Verification:

- Full build and tests pass: `./mvnw verify`
- `RegistrationRateLimiterTests` pass, including the concurrent-reserve test
- Integration test shows the 6th successful registration from one IP answers 429 and creates no company, while failed validations and duplicate emails leave the quota untouched

#### Manual Verification:

- On `./mvnw spring-boot:test-run`, registering 6 companies in a row from one browser shows the "too many registrations" message on the 6th, with name and email kept
- After a restart of the app the same browser can register again (accepted in-memory behaviour)
- On the deployed `test.regavio.com`, an INFO log line for a rejected registration shows the real client IP (from `CF-Connecting-IP`), not the Traefik/tunnel address

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- `RegistrationRateLimiterTests`: window boundaries, reserve/release, pruning, per-IP independence, and concurrency with a fixed/mutable `Clock`.

### Integration Tests:

- `RegistrationIntegrationTests` against real Postgres (Testcontainers):
  - honeypot, missing timestamp and too-fast rejections create nothing
  - the 429 cap
  - quota untouched by failed validation and by duplicate email
  - the header precedence
  - all existing cases kept green via a stamped session and a unique IP per call

### Manual Testing Steps:

1. Register normally on `/register`. Success, no visible change.
2. Load `/register` and submit immediately. Neutral error, fields kept, and a second submit after a few seconds succeeds.
3. Register 6 companies from one browser. The 6th gets the rate-limit message.
4. Check the deployed logs show the real client IP on a rejection.

## Performance Considerations

The checks run in memory before any database work, so rejected bot traffic never reaches bcrypt or Postgres. The limiter map is pruned on every call, which bounds memory by the IPs active in the last window.

## Migration Notes

No schema change and no new env var is required, since all three properties have defaults. The rate-limit state starts empty on every deploy.

## References

- Roadmap: `context/foundation/roadmap.md` S-10 (lines ~211-222)
- PRD: `context/foundation/prd-v2.md` FR-023 (line 94-95), NFR (lines 142-143)
- Prior slice: `context/archive/2026-11-05-company-registration/plan.md`
- Controller: `src/main/java/pl/regavio/stockahead/account/RegistrationController.java:60-112`
- Security: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java:47`
- Deployment topology: `context/foundation/deployment.md` (Ports and hostnames)
- Lessons: `context/foundation/lessons.md` "Handle DB constraint violations at write boundaries"

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Honeypot and minimum fill time

#### Automated

- [x] 1.1 Full build and tests pass: `./mvnw verify` — 3f4efbd
- [x] 1.2 New honeypot, missing-timestamp and too-fast tests in `RegistrationIntegrationTests` pass and assert no company row was created — 3f4efbd
- [x] 1.3 Existing `RegistrationIntegrationTests` cases pass unchanged in intent through the stamped-session helper — 3f4efbd

#### Manual

- [x] 1.4 On `./mvnw spring-boot:test-run`, a person filling `/register` normally registers successfully with no visible extra field or delay — 3f4efbd
- [x] 1.5 Submitting the form within ~1 s of loading it shows the neutral error with name and email kept (e.g. browser autofill and an immediate click, or `curl` with the session cookie and CSRF token) — 3f4efbd
- [x] 1.6 Tab order on `/register` skips the honeypot, and a screen reader does not announce it — 3f4efbd

### Phase 2: Per-IP registration rate limit

#### Automated

- [x] 2.1 Full build and tests pass: `./mvnw verify` — 13d794d
- [x] 2.2 `RegistrationRateLimiterTests` pass, including the concurrent-reserve test — 13d794d
- [x] 2.3 Integration test shows the 6th successful registration from one IP answers 429 and creates no company, while failed validations and duplicate emails leave the quota untouched — 13d794d

#### Manual

- [x] 2.4 On `./mvnw spring-boot:test-run`, registering 6 companies in a row from one browser shows the "too many registrations" message on the 6th, with name and email kept — 13d794d
- [x] 2.5 After a restart of the app the same browser can register again (accepted in-memory behaviour) — 13d794d
- [ ] 2.6 On the deployed `test.regavio.com`, an INFO log line for a rejected registration shows the real client IP (from `CF-Connecting-IP`), not the Traefik/tunnel address

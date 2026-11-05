# Company Registration Implementation Plan

## Overview

Roadmap slice S-09: a visitor registers a company on a public `/register` page (company name, email, password) and becomes its manager. The company starts as PENDING (FR-023). Until the operator approves it (S-11), its users can log in, but every warehouse route sends them to an "awaiting approval" page (FR-024, Guardrail "users of a pending or blocked company cannot reach any warehouse feature").

The frame `context/changes/per-company-registration-token/frame.md` replaced a "per-company join token" idea with this slice. The founder creates the company and becomes its manager in one step, so no token is needed. The frame adds two inputs: no public page lists companies, and the email-enumeration unknown must be decided here.

## Current State Analysis

- The schema already supports a pending company. `companies.status` allows `PENDING` with default `ACTIVE`, and `companies.name` is `NOT NULL CHECK (btrim(name) <> '')` with **no** unique constraint (`db/migration/V14__create_companies.sql`). `CompanyStatus.PENDING` exists (`account/CompanyStatus.java`). **No migration is needed.**
- An account is a MANAGER with a non-null `company_id` (`V20__operator_role.sql`: OPERATOR ⇔ `company_id IS NULL`). Email is globally unique through `accounts_email_canonical_idx` on `lower(btrim(email))` (`V4__normalize_account_emails.sql`).
- `AccountCredentials.validate(email, password, confirmPassword)` is the shared rule set: canonical email, at most 255 characters, format check, password ≥ 12, confirmation. It returns message keys (`account/AccountCredentials.java`). The accounts page and `/setup` both use it.
- `SetupController` (`account/SetupController.java:68-117`) shows the public-form pattern: validate, write inside a `TransactionTemplate`, catch `DataIntegrityViolationException` → re-render with `accounts.error.duplicateEmail`, redirect to `/login?setup`. Its advisory lock is setup-only and must not be reused (`SetupController.java:30-35`).
- URL authorization denies by default (`security/SecurityConfig.java:35-40`). Only `/login`, `/setup/**`, health and static resources are public. `/` and `/error` need any authenticated user, `/operator/**` needs OPERATOR, and everything else needs MANAGER or TECHNICIAN. A pending company's manager has `ROLE_MANAGER` (`security/AccountUserDetailsService.java:42-49`), so **today they would reach every warehouse route**. The pending gate is new work.
- `AccountActivityFilter` (`security/AccountActivityFilter.java:72-89`) already loads the principal's account on every authenticated request via `findByIdAndCompanyId` / `findByIdAndCompanyIdIsNull`. It logs out deactivated accounts and accounts whose role changed. `Account.company` is `LAZY` (`account/Account.java:39-41`), and the filter runs outside any open session, so reading `company.getStatus()` there needs a fetch join.
- The topbar shows warehouse links to anyone with `ROLE_MANAGER`/`ROLE_TECHNICIAN` (`templates/fragments.html:12-25`). `TemplateConventionTests` requires the topbar on every template except `login.html` and `setup.html` (`TemplateConventionTests.java:26`).
- The operator's company list already renders PENDING as "Oczekuje" (`templates/operator-companies.html:26`) and shows the first-created manager's email, so a registrant appears there with no change.
- `OperatorAccessIntegrationTests` keeps one test per warehouse controller (`account/OperatorAccessIntegrationTests.java:296-450`). That is the pattern for the pending gate's per-controller tests. `CompanyFixtures.company(name)` always creates an ACTIVE company (`CompanyFixtures.java:77-87`).

## Desired End State

An anonymous visitor opens `/login`, follows "Zarejestruj firmę" to `/register`, and submits company name, email, password and confirmation. A PENDING company and its active MANAGER account are created atomically, and the visitor lands on `/login?registered` with a success message. On login they reach only `/awaiting-approval`. Any warehouse URL, GET or POST, redirects there and changes nothing, and so does `/`. Once the company's status becomes ACTIVE (S-11, or manually with `UPDATE companies SET status = 'ACTIVE'`), the same session reaches the dashboard on its next request. A duplicate email re-renders the form with the explicit duplicate message and leaves no orphan company. No public page lists or names any existing company.

Verify with `./mvnw verify` (new gate and registration tests green, all existing tests unchanged) and the manual steps under Testing Strategy.

### Key Discoveries:

- No migration is needed: V14 already allows PENDING and non-unique names (`V14__create_companies.sql`).
- The filter is the single per-request place that loads the account (`AccountActivityFilter.java:77-80`). Extending it gives "approval takes effect on the next request" and is reusable for S-12.
- Lazy `Account.company` + filter outside OSIV → the lookup must fetch the company in the same query.
- Duplicate-email handling must catch `DataIntegrityViolationException` at the write boundary (lessons.md "Handle DB constraint violations at write boundaries").
- `findByCanonicalEmail` is login-only and guarded by `AccountLookupConventionTests`. Registration must not pre-check emails with it; it relies on the unique index.

## What We're NOT Doing

- No per-company join or invite token (frame verdict: no "join an existing company" need).
- No bot protection or rate limiting (S-10).
- No approve/reject/block actions, and no login refusal or special messages for REJECTED/BLOCKED companies (S-11, S-12). This slice only keeps any non-ACTIVE company's users out of the warehouse.
- No neutral "registration received" response for taken emails. Decided: the explicit duplicate error, with the enumeration risk accepted.
- No auto-login after registration.
- Registration is not closed while no operator exists. It is always open.
- No email verification, notifications or password reset (prd-v2 Open Question 2).
- No unique company names, and no "name taken" message.
- No company list, picker or company name on any public page.
- No schema migration and no change to `CompanyStatus` or the DB default.
- No dev seeder (S-08 decision stands).

## Implementation Approach

Gate first, then registration. Phase 1 makes every non-ACTIVE company's users land on `/awaiting-approval`, proven with fixture-created PENDING companies. That way no commit on the branch exposes the warehouse to a pending manager. Phase 2 adds the public form, which only creates PENDING companies the gate already contains.

The gate lives in `AccountActivityFilter`. It already loads the account on every request, so it now also fetches the company's status. A redirect, rather than a 403, gives the pending user the page FR-024 asks for. The URL deny-by-default and `@PreAuthorize` layers stay unchanged behind it.

## Critical Implementation Details

- **Filter order and exemptions**: the gate redirect must not apply to `/awaiting-approval` itself, to `/error` or the ERROR dispatch, or to static resources (CSS on the awaiting page), or the user loops. Logout is handled by `LogoutFilter` earlier in the chain and never reaches this filter. Pass the exemption matcher in from `SecurityConfig`, reusing `PathRequest.toStaticResources().atCommonLocations()`, so the public/static definition stays in one place.
- **Check order inside the filter**: deactivated/missing still wins, then role-changed, then company-not-active. A deactivated pending manager goes to `/login?deactivated`, not to the awaiting page.
- **Atomic registration**: create the company and the account in one `TransactionTemplate`. A unique-index violation on the account insert must roll back the company row too. The test asserts no orphan company.

## Phase 1: Pending-company gate

### Overview

Users of a company whose status is not ACTIVE can log in but reach only `/awaiting-approval`. Every other authenticated non-operator route, including `/`, redirects there and performs nothing. When the status flips to ACTIVE, the same session works on its next request.

### Changes Required:

#### 1. Account lookup with company

**File**: `src/main/java/pl/regavio/stockahead/account/AccountRepository.java`

**Intent**: Give the filter a scoped lookup that also loads the account's company, so it can read the status without a lazy-load outside a session.

**Contract**: A new scoped `(id, company_id)` lookup that fetch-joins `company` (JPQL `JOIN FETCH` or `@EntityGraph(attributePaths = "company")`), returning `Optional<Account>`. It is not an email lookup, so `AccountLookupConventionTests` is unaffected. `findByIdAndCompanyId` stays for the other callers.

#### 2. Company-status gate in the activity filter

**File**: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`

**Intent**: After the existing deactivated and role-changed checks, if the principal has a company and that company's status is not `ACTIVE`, redirect to `/awaiting-approval` without invalidating the session, unless the request matches the exemption matcher. Update the class Javadoc to describe the third outcome.

**Contract**: A new state, e.g. `COMPANY_NOT_ACTIVE`, is evaluated after `DEACTIVATED` and `ROLE_CHANGED`. The operator branch (`companyId == null`) is unchanged. The constructor takes a `RequestMatcher` of gate-exempt requests in addition to `AccountRepository`. The redirect target is `contextPath + "/awaiting-approval"`. The session is kept, so S-11 approval applies on the next request.

#### 3. Wiring and the exemption matcher

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`

**Intent**: Build the gate-exempt matcher (`/awaiting-approval`, `/error`, the ERROR dispatcher type, static resources) and pass it to the filter. Update the class Javadoc. URL rules are unchanged: `/awaiting-approval` falls under `anyRequest().hasAnyRole("MANAGER", "TECHNICIAN")`, so the operator gets 403 there.

**Contract**: `new AccountActivityFilter(accountRepository, gateExempt)`, with `gateExempt` an `OrRequestMatcher` of the four entries above.

#### 4. Awaiting-approval page

**File**: `src/main/java/pl/regavio/stockahead/account/AwaitingApprovalController.java` (new), `src/main/resources/templates/awaiting-approval.html` (new), `src/main/resources/messages.properties`

**Intent**: `GET /awaiting-approval` renders a standalone card ("Twoja firma oczekuje na zatwierdzenie przez operatora." plus the signed-in email and a logout button). There is no warehouse topbar, because a pending manager holds `ROLE_MANAGER` and would otherwise see warehouse links. A user of an ACTIVE company who opens it is redirected to `/`.

**Contract**: The route is `GET /awaiting-approval` with `@PreAuthorize("hasAnyRole('MANAGER','TECHNICIAN')")`. The controller reads the company status through the principal's `(accountId, companyId)`, never from a parameter. The template uses `fragments :: head(...)` and the `auth-page`/`card` layout like `login.html`. It shows no company list and no styling outside `app.css`. Messages live under a new `awaiting.*` prefix.

#### 5. Template convention

**File**: `src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`

**Intent**: Allow `awaiting-approval.html` to omit the topbar. It is authenticated but deliberately shows no navigation.

**Contract**: Rename the exemption set (e.g. `VIEWS_WITHOUT_TOPBAR`) and add `awaiting-approval.html`. The head-fragment and no-inline-style checks still apply to it.

#### 6. Fixtures and tests

**File**: `src/test/java/pl/regavio/stockahead/CompanyFixtures.java`, `src/test/java/pl/regavio/stockahead/security/AccountActivityFilterTest.java`, `src/test/java/pl/regavio/stockahead/account/PendingCompanyAccessIntegrationTests.java` (new)

**Intent**: The fixture gets an overload that creates a company with a given status. The filter unit tests cover the new state and its ordering. The integration class mirrors `OperatorAccessIntegrationTests`: one test per warehouse controller showing that a pending manager is redirected to `/awaiting-approval` and nothing changes.

**Contract**: `CompanyFixtures.company(String name, CompanyStatus status)`; the existing `company(name)` delegates with `ACTIVE`. The test list is in Testing Strategy.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- `AccountActivityFilterTest` covers: pending-company user redirected to `/awaiting-approval` with the session kept; exempt request passes; deactivated pending user goes to `/login?deactivated` (precedence); active-company user passes; operator unaffected
- `PendingCompanyAccessIntegrationTests` passes: a pending manager is redirected from `/` and from one route per warehouse controller, and a mutating POST changes nothing
- `PendingCompanyAccessIntegrationTests` passes: after `UPDATE companies SET status = 'ACTIVE'`, the same session reaches `/` (dashboard) on its next request
- `PendingCompanyAccessIntegrationTests` passes: `/awaiting-approval` renders for the pending manager, redirects an active-company manager to `/`, and answers the operator with 403
- `TemplateConventionTests` passes with `awaiting-approval.html` exempt only from the topbar rule

#### Manual Verification:

- With an SQL-seeded PENDING company and manager on `./mvnw spring-boot:test-run`, logging in lands on the styled awaiting page; typing `/parts` or `/orders` returns there; logout works
- After `UPDATE companies SET status = 'ACTIVE'`, refreshing in the same session reaches the dashboard

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Public company registration

### Overview

An anonymous visitor registers a company on `/register`. A PENDING company and its active manager are created atomically, and the visitor is sent to log in. Duplicate emails get the explicit error. The login page links to registration.

### Changes Required:

#### 1. Public route

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`

**Intent**: Make `/register` public. This is the first public write endpoint. CSRF stays enabled, as for every form.

**Contract**: Add `"/register"` to the `permitAll` matcher alongside `/login`, `/setup/**` and health. Update the Javadoc list of public pages.

#### 2. Registration controller

**File**: `src/main/java/pl/regavio/stockahead/account/RegistrationController.java` (new)

**Intent**: `GET /register` shows the form. An already-authenticated user is redirected to `/`. `POST /register` validates the company name, then the credentials through `AccountCredentials.validate`. In one transaction it creates a `Company` (trimmed name, `PENDING`, `createdAt = now`) and an `Account` (canonical email, encoded password, `MANAGER`, active, same `createdAt`, that company). On success it redirects to `/login?registered`. Any validation failure re-renders the form with the first error and keeps the company name and email, never the passwords.

**Contract**:
- `POST /register` takes the params `companyName`, `email`, `password`, `confirmPassword`.
- Company name: trimmed, error `register.error.companyNameRequired` if blank, `register.error.companyNameTooLong` (argument 255) if longer than 255. The name is checked before the credentials. Duplicate names are allowed.
- `DataIntegrityViolationException` → re-render with `accounts.error.duplicateEmail`, and the transaction rolls back the company row.
- No advisory lock (the setup lock must not be reused). No email pre-check via `findByCanonicalEmail`.

#### 3. Registration page and login link

**File**: `src/main/resources/templates/register.html` (new), `src/main/resources/templates/login.html`, `src/main/resources/messages.properties`

**Intent**: The registration card follows the `setup.html` layout: company name, email, password, confirm password, a submit button ("Zarejestruj firmę") and a link back to `/login`. It never shows a list or name of existing companies. Login gains a "Zarejestruj firmę" link to `/register` and a `?registered` success alert ("Firma została zarejestrowana i czeka na zatwierdzenie. Zaloguj się.").

**Contract**: `register.html` uses `fragments :: head(...)` and `fragments :: alerts`, with no topbar. Add it to the template-convention exemption set from Phase 1. Field constraints are `maxlength="255"` for the name and email and `minlength="12"` for the passwords. New message keys: `register.error.companyNameRequired`, `register.error.companyNameTooLong`, `login.status.registered`. Do not change existing login markup that tests match exactly. Add elements instead.

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/account/RegistrationIntegrationTests.java` (new), `src/test/java/pl/regavio/stockahead/TemplateConventionTests.java`

**Intent**: Cover the public endpoint end to end against real Postgres, including the constraint-violation path and the full register → login → awaiting flow.

**Contract**: Two-company/fixture conventions as in `CompanyAccountIntegrationTests`. Rows created through `/register` aren't tracked by `CompanyFixtures`, so the test cleans up its own companies and accounts. The case list is in Testing Strategy.

#### 5. Documentation

**File**: `AGENTS.md`, `context/foundation/roadmap.md`

**Intent**: Record the new public route and the gate so future slices keep them.

**Contract**:
- AGENTS.md § Company isolation: the public-route list includes `/register`.
- A new bullet: users of a non-ACTIVE company are redirected to `/awaiting-approval` by `AccountActivityFilter`. `PendingCompanyAccessIntegrationTests` holds one case per warehouse controller; add a case when you add a controller.
- Roadmap S-09 Unknowns are marked answered (2026-11-05): explicit duplicate-email error accepted; registration reuses `AccountCredentials`.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- `RegistrationIntegrationTests` passes: anonymous `GET /register` renders the form without any existing company's name, and an authenticated user is redirected to `/`
- `RegistrationIntegrationTests` passes: a valid POST creates exactly one PENDING company with the trimmed name and one active MANAGER with the canonical email in it, then redirects to `/login?registered`
- `RegistrationIntegrationTests` passes: blank or over-255 company name, and each `AccountCredentials` failure, re-render with the right message and create nothing
- `RegistrationIntegrationTests` passes: an email already used by a company account or by the operator (case/whitespace variant) re-renders with the duplicate message and leaves no orphan company
- `RegistrationIntegrationTests` passes: a second registration with an existing company's name succeeds
- `RegistrationIntegrationTests` passes: a POST without a CSRF token is rejected
- `RegistrationIntegrationTests` passes: the registered manager logs in, is sent to `/awaiting-approval`, and the operator's company list shows the company as pending with that email

#### Manual Verification:

- On a fresh `./mvnw spring-boot:test-run`: login → "Zarejestruj firmę" → register → success message on login → log in → awaiting page; the operator sees the company as "Oczekuje"
- A duplicate email and a too-short password show readable errors and keep the typed company name and email
- After `UPDATE companies SET status = 'ACTIVE'` the registrant reaches the dashboard and can create a technician

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- `AccountActivityFilterTest`: pending company → redirect `/awaiting-approval`, session not invalidated; REJECTED/BLOCKED company → same redirect (fail-closed); exempt request (awaiting page, static, error) passes through; deactivated pending account → `/login?deactivated`; role-changed pending account → `/login?roleChanged`; ACTIVE company passes; operator path unchanged.

### Integration Tests:

- `PendingCompanyAccessIntegrationTests` (new; one case per controller listed in `OperatorAccessIntegrationTests`): a pending manager's GET to each controller's main route, and a POST to its mutating route, redirect to `/awaiting-approval`, with the state asserted unchanged via `jdbcTemplate`. `/` also redirects. The awaiting page renders for pending, redirects for active, and gives the operator 403. The status flips to ACTIVE in the same session → dashboard. The CSS under `/css/**` is reachable for a pending user.
- `RegistrationIntegrationTests` (new): the cases listed in Phase 2's success criteria, plus register → login → awaiting.
- Existing suites (`OperatorAccessIntegrationTests`, `AuthenticationIntegrationTests`, `SecurityRedirectTests`, `AccountActivityFilterTest`) stay green. Fixture companies remain ACTIVE by default.

### Manual Testing Steps:

1. `./mvnw spring-boot:test-run`, complete `/setup` to create the operator.
2. Log out, open `/login`, follow "Zarejestruj firmę", register "Firma A" / `a@example.com`.
3. Log in as `a@example.com` → awaiting page. Type `/parts` → back on the awaiting page.
4. Register again with `A@Example.com ` → duplicate-email error; the form keeps the company name.
5. Log in as the operator → "Firma A", `a@example.com`, "Oczekuje".
6. `UPDATE companies SET status = 'ACTIVE' WHERE name = 'Firma A';` → the registrant refreshes and reaches the dashboard.

## Performance Considerations

The filter's per-request lookup now also joins `companies` by primary key in the same query. That is negligible at the PRD's "handful of companies" scale, and it adds no extra round-trip.

## Migration Notes

None. No schema change. Existing fixture companies and any SQL-inserted companies default to ACTIVE, so they keep working.

## References

- Frame: `context/changes/per-company-registration-token/frame.md`
- PRD: `context/foundation/prd-v2.md` FR-023, FR-024, FR-029, Business Logic Changes (company lifecycle), Access Control Changes
- Roadmap: `context/foundation/roadmap.md` S-09 (Unknowns, Risk), S-10, S-11, S-12
- Prior slice: `context/changes/operator-setup-and-company-list/plan-brief.md` (deny-by-default, `AccountCredentials`, "future public page must be added to permitAll")
- Patterns: `account/SetupController.java:68-117` (public form + constraint catch), `security/AccountActivityFilter.java:72-89` (per-request lookup), `account/OperatorAccessIntegrationTests.java:296-450` (per-controller guardrail tests)
- Lessons: "Handle DB constraint violations at write boundaries", "Build every template on the shared head and stylesheet"

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Pending-company gate

#### Automated

- [x] 1.1 Full build and test suite passes: `./mvnw verify`
- [x] 1.2 `AccountActivityFilterTest` covers: pending-company user redirected to `/awaiting-approval` with the session kept; exempt request passes; deactivated pending user goes to `/login?deactivated` (precedence); active-company user passes; operator unaffected
- [x] 1.3 `PendingCompanyAccessIntegrationTests` passes: a pending manager is redirected from `/` and from one route per warehouse controller, and a mutating POST changes nothing
- [x] 1.4 `PendingCompanyAccessIntegrationTests` passes: after `UPDATE companies SET status = 'ACTIVE'`, the same session reaches `/` (dashboard) on its next request
- [x] 1.5 `PendingCompanyAccessIntegrationTests` passes: `/awaiting-approval` renders for the pending manager, redirects an active-company manager to `/`, and answers the operator with 403
- [x] 1.6 `TemplateConventionTests` passes with `awaiting-approval.html` exempt only from the topbar rule

#### Manual

- [x] 1.7 With an SQL-seeded PENDING company and manager on `./mvnw spring-boot:test-run`, logging in lands on the styled awaiting page; typing `/parts` or `/orders` returns there; logout works
- [x] 1.8 After `UPDATE companies SET status = 'ACTIVE'`, refreshing in the same session reaches the dashboard

### Phase 2: Public company registration

#### Automated

- [ ] 2.1 Full build and test suite passes: `./mvnw verify`
- [ ] 2.2 `RegistrationIntegrationTests` passes: anonymous `GET /register` renders the form without any existing company's name, and an authenticated user is redirected to `/`
- [ ] 2.3 `RegistrationIntegrationTests` passes: a valid POST creates exactly one PENDING company with the trimmed name and one active MANAGER with the canonical email in it, then redirects to `/login?registered`
- [ ] 2.4 `RegistrationIntegrationTests` passes: blank or over-255 company name, and each `AccountCredentials` failure, re-render with the right message and create nothing
- [ ] 2.5 `RegistrationIntegrationTests` passes: an email already used by a company account or by the operator (case/whitespace variant) re-renders with the duplicate message and leaves no orphan company
- [ ] 2.6 `RegistrationIntegrationTests` passes: a second registration with an existing company's name succeeds
- [ ] 2.7 `RegistrationIntegrationTests` passes: a POST without a CSRF token is rejected
- [ ] 2.8 `RegistrationIntegrationTests` passes: the registered manager logs in, is sent to `/awaiting-approval`, and the operator's company list shows the company as pending with that email

#### Manual

- [ ] 2.9 On a fresh `./mvnw spring-boot:test-run`: login → "Zarejestruj firmę" → register → success message on login → log in → awaiting page; the operator sees the company as "Oczekuje"
- [ ] 2.10 A duplicate email and a too-short password show readable errors and keep the typed company name and email
- [ ] 2.11 After `UPDATE companies SET status = 'ACTIVE'` the registrant reaches the dashboard and can create a technician

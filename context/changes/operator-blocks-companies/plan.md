# Operator Blocks and Unblocks Companies Implementation Plan

## Overview

Roadmap slice S-12 (FR-027, nice-to-have). The operator blocks an ACTIVE company so its users can no longer log in, and unblocks a BLOCKED company so all of them can log in again with their data intact. A blocked user who enters the correct password sees a "company blocked" message on the login page. A session that was open when the block happened is held on the gate page from its next request, and the same session works again after unblock.

## Current State Analysis

- `CompanyStatus.BLOCKED` already exists (`account/CompanyStatus.java`), and V14 already allows it in the status CHECK (`V14__create_companies.sql:19`). **No migration is needed.**
- `CompanyLifecycle` (`account/CompanyLifecycle.java`) is the single place that writes a company's status. It locks the row with `CompanyRepository.findByIdForUpdate`, re-reads the status, and turns a transition from the wrong state into a no-op `Outcome.NOT_PENDING`. It only knows `approve` and `reject` today. AGENTS.md already says block/unblock go here.
- **Login:** `AccountUserDetailsService.loadUserByUsername` builds a `CompanyUser` whose `enabled` flag is `account.isActive()`. Company status is not checked, so **a user of a BLOCKED company can log in today**. `SecurityConfig.authenticationProvider` is a plain `DaoAuthenticationProvider`. Form login uses the default failure URL `/login?error`, which shows the generic `login.error.badCredentials`.
- **Open sessions:** `AccountActivityFilter` already sends every account of a non-ACTIVE company (pending, rejected or blocked) to `/awaiting-approval`, keeping the session. The released, deactivated and role-changed checks win first. `AwaitingApprovalController` renders `awaiting-approval.html`, which always says "Twoja firma oczekuje na zatwierdzenie przez operatora." That text is wrong for a blocked company.
- **Operator list:** `operator-companies.html` already has an "Akcje" column (shown only on PENDING rows) and a "Zablokowana" badge. `OperatorCompanyController` copies the PRG + flash pattern (`success`/`info`/`error`) and the reject confirmation page (`operator-companies-reject.html`, `GET`/`POST /operator/companies/{id}/reject`).

## Desired End State

- On `/operator/companies`, an ACTIVE row shows "Zablokuj". That button opens a confirmation page naming the company and its manager's email, and the confirmation posts the block. A BLOCKED row shows "Odblokuj", which posts directly. PENDING rows keep "Zatwierdź"/"Odrzuć". REJECTED rows have no actions.
- A user of a BLOCKED company who logs in with the correct password is not logged in and lands on `/login?blocked`, which reads "Twoja firma została zablokowana przez operatora." A wrong password, an unknown email, or a deactivated account of a blocked company still gets the generic "Nieprawidłowy e-mail lub hasło.".
- A session that was open when the company was blocked is redirected on its next request to `/awaiting-approval`, which then shows blocked wording (title and message) instead of "awaiting approval". After unblock, the same session reaches the dashboard on its next request.
- Blocking and unblocking change only `companies.status`. Every account's `active` flag, role and email stay as they were, so a technician deactivated before the block is still deactivated after unblock. The company's parts, projects and orders are untouched.
- Stale or concurrent clicks (block twice, block vs unblock, block on a company that just became BLOCKED or REJECTED) change nothing and return the "Status firmy zmienił się" info flash.

### Key Discoveries:

- `DaoAuthenticationProvider` in Security 7.1.1 exposes `setPostAuthenticationChecks(UserDetailsChecker)`. These checks run **after** the password matches, while pre-checks (disabled, locked) run **before** it. Verified with `javap` against `spring-security-core-7.1.1.jar`.
- `ExceptionMappingAuthenticationFailureHandler` (spring-security-web 7.1.1) maps an exception class name to a failure URL and falls back to its default failure URL.
- Gate page reuse: `/awaiting-approval` is already in the filter's `gateExempt` matcher (`SecurityConfig.java:61-65`), so showing status-specific wording on that one route needs no new exempt path.
- Tests to extend: `OperatorCompanyLifecycleIntegrationTests` (`listShowsActionsOnlyOnPendingRows` must change, `actionsOnActiveOrRejectedCompanyChangeNothingAndShowStatusChanged`, 404/403/anonymous/CSRF cases), `CompanyLifecycleConcurrencyTests.simultaneousApproveAndRejectLeaveExactlyOneOutcome`, and `PendingCompanyAccessIntegrationTests:133` (already seeds BLOCKED by SQL). `CompanyFixtures.company(name, CompanyStatus)` seeds a company in any status.

## What We're NOT Doing

- Ending open sessions immediately on block (parked in the roadmap). Open sessions are gated on their next request, not invalidated.
- Letting open sessions keep warehouse access until logout. prd-v2 says blocked users cannot reach any warehouse feature.
- Blocking PENDING or REJECTED companies, or unblocking into any status other than ACTIVE.
- Changing account `active` flags, releasing emails, or deleting anything on block.
- A block reason, a block history/audit table, or email notifications.
- A separate `/company-blocked` route, or a JavaScript confirm dialog.
- Any schema migration.

## Implementation Approach

Phase 1 teaches login and the gate page what "blocked" means, tested against companies seeded as BLOCKED, before any operator UI exists. Phase 2 adds the two `CompanyLifecycle` transitions and the operator routes/UI on the existing lock + flash pattern, then proves block → unblock end to end.

## Critical Implementation Details

- **Login check ordering:** the blocked check must be a *post*-authentication check (`setPostAuthenticationChecks`). If it were a pre-check (for example `accountNonLocked=false`), the "blocked" message would show for any password and reveal that the email exists. The replacement checker replaces the default post-check (credentials non-expired). `CompanyUser` always passes `credentialsNonExpired=true`, so that check never fires today, but keep it by throwing `CredentialsExpiredException` when `!isCredentialsNonExpired()` before the blocked check. `getPostAuthenticationChecks()` is protected, so it cannot be delegated to from a bean method. A deactivated account of a blocked company fails the disabled pre-check first and keeps the generic message.
- **Gate vs login:** the filter's `COMPANY_NOT_ACTIVE` branch must not invalidate a BLOCKED session. The roadmap parks immediate logout, and unblock must restore the same session.

## Phase 1: Blocked company refuses login and shows its own gate wording

### Overview

After this phase, a user of a BLOCKED company cannot log in and gets a specific message, but only after typing the correct password. An already-open session of a BLOCKED company sees blocked wording on the gate page. Nothing sets BLOCKED yet except tests.

### Changes Required:

#### 1. Principal knows its company is blocked

**File**: `src/main/java/pl/regavio/stockahead/security/CompanyUser.java`, `src/main/java/pl/regavio/stockahead/security/AccountUserDetailsService.java`

**Intent**: Carry, at login time, whether the account's company is BLOCKED, so the post-authentication check can refuse the login without another query.

**Contract**: `CompanyUser` gains a `companyBlocked()` boolean (false for the operator). Bump `serialVersionUID`. `loadUserByUsername` sets it from `account.getCompany().getStatus() == CompanyStatus.BLOCKED`. The existing constructor callers (tests, fixtures) get an overload or are updated. The filter keeps re-reading status from the DB on every request and never trusts this flag.

#### 2. Post-password blocked check and failure mapping

**File**: `src/main/java/pl/regavio/stockahead/security/SecurityConfig.java`, new `src/main/java/pl/regavio/stockahead/security/CompanyBlockedException.java`

**Intent**: Refuse login for a blocked company only once the password is verified, and send that failure to `/login?blocked`. Every other failure keeps going to `/login?error`.

**Contract**: `CompanyBlockedException extends AccountStatusException`. `authenticationProvider` installs a post-authentication `UserDetailsChecker` that keeps the default credentials-expired check and then throws `CompanyBlockedException` when `CompanyUser.companyBlocked()`. `formLogin` uses an `ExceptionMappingAuthenticationFailureHandler` that maps `CompanyBlockedException` to `/login?blocked`, with default failure URL `/login?error`.

#### 3. Login page message

**File**: `src/main/resources/templates/login.html`, `src/main/resources/messages.properties`

**Intent**: Show the blocked reason on `/login?blocked`.

**Contract**: New key `login.status.companyBlocked=Twoja firma została zablokowana przez operatora.`, rendered as `<p th:if="${param.blocked}" class="alert alert-error" role="alert">`, the same markup as the deactivated line. No inline styles (`TemplateConventionTests`).

#### 4. Gate page wording per status

**File**: `src/main/java/pl/regavio/stockahead/account/AwaitingApprovalController.java`, `src/main/resources/templates/awaiting-approval.html`, `src/main/resources/messages.properties`

**Intent**: The one gate page tells a blocked company's user that the company is blocked, not that it is awaiting approval. Pending users still see the existing text.

**Contract**: The controller adds the company's status (read through `findWithCompanyByIdAndCompanyId`, as today) to the model. The template switches the `<head>` title, `<h1>` and the info message on `BLOCKED`. New keys are `awaiting.blocked.title=Firma zablokowana` and `awaiting.status.blocked=Twoja firma została zablokowana przez operatora. Dane firmy są zachowane.`. ACTIVE still redirects to `/`. Update the `AccountActivityFilter` Javadoc only if its wording about the gate becomes inaccurate.

#### 5. Tests

**File**: `src/test/java/pl/regavio/stockahead/security/` and `src/test/java/pl/regavio/stockahead/account/` (new `BlockedCompanyLoginIntegrationTests`, or cases in an existing login test class)

**Intent**: Pin the login matrix and the gate wording against real Postgres, with companies seeded via `CompanyFixtures.company(name, CompanyStatus.BLOCKED)`.

**Contract**: These cases (form login through MockMvc with CSRF):
- Manager and technician of a BLOCKED company, correct password → redirect `/login?blocked`, unauthenticated.
- Wrong password → `/login?error`.
- Deactivated account of a BLOCKED company, correct password → `/login?error`.
- PENDING company → still logs in and lands on the gate.
- ACTIVE company → logs in.
- `/login?blocked` renders the blocked message.
- An authenticated session of a BLOCKED company on a warehouse route → `/awaiting-approval`, which shows the blocked title and message, not the pending one. PENDING still shows the pending message.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- Blocked-login integration tests pass (correct password → `/login?blocked`; wrong password and deactivated account → `/login?error`; pending and active unchanged)
- Gate page shows blocked wording for a BLOCKED company and pending wording for a PENDING one

#### Manual Verification:

- With a company set to BLOCKED in the DB (`./mvnw spring-boot:test-run`), logging in with the correct password shows "Twoja firma została zablokowana przez operatora." and a wrong password shows the generic message

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Block and unblock from the operator list

### Overview

The operator blocks an ACTIVE company through a confirmation page and unblocks a BLOCKED company directly from the list. Both go through `CompanyLifecycle` under the row lock, and stale clicks are no-ops.

### Changes Required:

#### 1. Lifecycle transitions

**File**: `src/main/java/pl/regavio/stockahead/account/CompanyLifecycle.java`

**Intent**: Add the two transitions on the existing lock path, and generalize the no-op outcome now that not every transition starts from PENDING.

**Contract**: Rename `Outcome.NOT_PENDING` to `Outcome.STATUS_CHANGED` and update its uses (only this class and `OperatorCompanyController` reference it). Add `@Transactional Result block(Long companyId)`, which goes ACTIVE → BLOCKED and returns `STATUS_CHANGED` from any other status. Add `@Transactional Result unblock(Long companyId)`, which goes BLOCKED → ACTIVE and returns `STATUS_CHANGED` from any other status. Neither touches accounts. Both throw `CompanyNotFoundException` for an unknown id. Update the class Javadoc.

#### 2. Operator routes

**File**: `src/main/java/pl/regavio/stockahead/account/OperatorCompanyController.java`

**Intent**: Expose block (with a confirmation page) and unblock using the same flash/PRG and `DataIntegrityViolationException` handling as approve/reject.

**Contract**: All four routes are `@PreAuthorize("hasRole('OPERATOR')")`:
- `GET /operator/companies/{id}/block` → `operator-companies-block`, using `findOperatorRowById`. Unknown id → 404. A company not ACTIVE → redirect to the list with the `statusChanged` info.
- `POST /operator/companies/{id}/block` → `companyLifecycle.block`. Success flash `operator.companies.blocked`.
- `POST /operator/companies/{id}/unblock` → `companyLifecycle.unblock`. Success flash `operator.companies.unblocked`.
- Update the class Javadoc.

#### 3. Templates and messages

**File**: new `src/main/resources/templates/operator-companies-block.html`, `src/main/resources/templates/operator-companies.html`, `src/main/resources/messages.properties`

**Intent**: Add a block confirmation page copied from the reject page. On the list, give ACTIVE and BLOCKED rows their actions.

**Contract**:
- The block page uses the shared head/topbar, the company name + manager email `<dl>`, an intro, a "Zablokuj firmę" `btn-danger` POST and an "Anuluj" link.
- List: ACTIVE → `<a class="btn btn-danger" href=".../block">Zablokuj</a>`. BLOCKED → a POST form with `<button class="btn">Odblokuj</button>`. PENDING is unchanged. REJECTED has none.
- New keys:
  - `operator.companies.blocked=Firma {0} została zablokowana.`
  - `operator.companies.unblocked=Firma {0} została odblokowana.`
  - `operator.companies.block.title=Blokada firmy`
  - `operator.companies.block.intro=Użytkownicy firmy nie będą mogli się zalogować, a otwarte sesje zobaczą informację o blokadzie. Dane firmy zostaną zachowane; odblokowanie przywraca dostęp.`
  - `operator.companies.block.confirm=Zablokuj firmę`
  - Reuse the existing reject `name`/`managerEmail`/`cancel` keys, or add block equivalents.

#### 4. Tests

**File**: `src/test/java/pl/regavio/stockahead/account/OperatorCompanyLifecycleIntegrationTests.java`, `src/test/java/pl/regavio/stockahead/account/CompanyLifecycleConcurrencyTests.java`

**Intent**: Prove the FR-027 story end to end, the restore-everyone guarantee, the state machine, and the access rules on the new routes.

**Contract**: These cases:
- **End to end:** ACTIVE company with a manager, an active technician, a deactivated technician and a part. A manager session is open. The operator blocks through the confirmation page. The open session's next request goes to the gate with blocked wording. A new login with the correct password goes to `/login?blocked`. The operator unblocks. The same open session reaches the dashboard. The manager and active technician log in. The deactivated technician still fails with `/login?error`. The part is still there for the company. No account's `active`/`email_released_at` changed.
- **Stale clicks:** block on PENDING/REJECTED/BLOCKED and unblock on PENDING/REJECTED/ACTIVE change nothing and flash `statusChanged`. `GET .../block` on a non-ACTIVE company redirects with `statusChanged`.
- **Access:** unknown id → 404 on all three new routes. Manager and technician → 403 and nothing changes. Anonymous → login redirect. Operator POST without CSRF → rejected, nothing changes.
- **List:** replace `listShowsActionsOnlyOnPendingRows` with a per-status action assertion (PENDING approve/reject, ACTIVE block link, BLOCKED unblock form, REJECTED none).
- **Concurrency:** simultaneous `block` and `unblock` on one ACTIVE company → exactly one `DONE`, final status consistent with that outcome. Simultaneous double `block` → one `DONE`, one `STATUS_CHANGED`, status BLOCKED.

#### 5. Repository guidance

**File**: `AGENTS.md`

**Intent**: Record the blocked-login rule so future login changes keep it.

**Contract**: In "Company isolation (prd-v2)", add one bullet. A BLOCKED company's users are refused at login by the post-authentication check in `security/SecurityConfig` (`/login?blocked`, shown only after the password matches). Open sessions are gated to `/awaiting-approval`. Block/unblock never touch account flags. The existing `CompanyLifecycle` bullet already covers where transitions live. Update it to say block/unblock now exist.

### Success Criteria:

#### Automated Verification:

- Full build and test suite passes: `./mvnw verify`
- Block → unblock end-to-end test passes (open session gated then restored, data and account flags intact, deactivated technician stays deactivated)
- Stale-click, 404, 403, anonymous and CSRF tests pass for block and unblock routes
- Block-vs-unblock and double-block concurrency tests leave exactly one applied transition

#### Manual Verification:

- In `./mvnw spring-boot:test-run`, the operator blocks an active company via the confirmation page, its manager's open tab shows the blocked page on refresh, and a new login shows the blocked message
- The operator unblocks it from the list and the manager's same tab reaches the dashboard with its parts intact

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Testing Strategy

### Unit Tests:

- `AccountActivityFilterTest` already covers BLOCKED → gate with the session kept (parameterized over non-ACTIVE statuses). No change is expected beyond keeping it green.

### Integration Tests:

- Login matrix for a blocked company (Phase 1). Block/unblock end to end, state machine, access and list actions (Phase 2). Lifecycle concurrency (Phase 2). All run against real Postgres via `TestcontainersConfiguration` + `CompanyFixtures`.

### Manual Testing Steps:

1. Start `./mvnw spring-boot:test-run`, create the operator, register and approve a company, and log in as its manager in a second browser.
2. As the operator, click "Zablokuj" → confirm. Refresh the manager tab, which shows "Firma zablokowana".
3. Log out the manager and log in again. The correct password shows the blocked message, and a wrong password shows the generic one.
4. As the operator, click "Odblokuj". The manager logs in and sees their parts.

## Performance Considerations

None. The login reads the company status already joined on the account. Block/unblock lock one company row.

## Migration Notes

None. `BLOCKED` is already in the enum and the V14 CHECK.

## References

- Roadmap item: `context/foundation/roadmap.md` § S-12
- PRD: `context/foundation/prd-v2.md` FR-027, Business Logic Changes (company lifecycle), Access Control Changes
- Prior slice: `context/archive/2026-11-08-operator-approves-companies/plan.md`
- Lifecycle service: `src/main/java/pl/regavio/stockahead/account/CompanyLifecycle.java`
- Gate filter: `src/main/java/pl/regavio/stockahead/security/AccountActivityFilter.java`
- Reject confirmation pattern: `src/main/resources/templates/operator-companies-reject.html`, `OperatorCompanyController.rejectForm`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Blocked company refuses login and shows its own gate wording

#### Automated

- [x] 1.1 Full build and test suite passes: `./mvnw verify`
- [x] 1.2 Blocked-login integration tests pass (correct password → `/login?blocked`; wrong password and deactivated account → `/login?error`; pending and active unchanged)
- [x] 1.3 Gate page shows blocked wording for a BLOCKED company and pending wording for a PENDING one

#### Manual

- [x] 1.4 With a company set to BLOCKED in the DB (`./mvnw spring-boot:test-run`), logging in with the correct password shows "Twoja firma została zablokowana przez operatora." and a wrong password shows the generic message

### Phase 2: Block and unblock from the operator list

#### Automated

- [ ] 2.1 Full build and test suite passes: `./mvnw verify`
- [ ] 2.2 Block → unblock end-to-end test passes (open session gated then restored, data and account flags intact, deactivated technician stays deactivated)
- [ ] 2.3 Stale-click, 404, 403, anonymous and CSRF tests pass for block and unblock routes
- [ ] 2.4 Block-vs-unblock and double-block concurrency tests leave exactly one applied transition

#### Manual

- [ ] 2.5 In `./mvnw spring-boot:test-run`, the operator blocks an active company via the confirmation page, its manager's open tab shows the blocked page on refresh, and a new login shows the blocked message
- [ ] 2.6 The operator unblocks it from the list and the manager's same tab reaches the dashboard with its parts intact

<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Company Boundary and Cross-Company Test Pattern

- **Plan**: context/changes/company-boundary-harness/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2
- **Date**: 2026-10-05
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Automated criteria were re-run on HEAD (c44fc89): `./mvnw verify` exit 0; `grep -rln "new Account()" src/test` → only `CompanyFixtures.java`, `AccountUserDetailsServiceTest.java`; `grep -n "findById(" …/TechnicianAccountController.java` → no match. Manual rows 1.6–1.8 and 2.4–2.5 were confirmed by the user in-session. All planned items MATCH; extras (`MessagesBundleTests` keys, over-255 setup test, fixture cleanup of UI-created accounts, `maxlength`/`required` on the field) are benign.

## Findings

### F1 — Order assignee lookup is still cross-company

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:268, :550; src/main/java/pl/regavio/stockahead/account/AccountRepository.java:34 (`findAssignable`)
- **Detail**: Order create and assign load the assignee with a bare `accountRepository.findById(parsedAssigneeId)`, and the picker lists `findAssignable()` across all companies. Accounts are now company-owned, so this contradicts AGENTS.md ("never a bare findById on a company-owned entity"). The plan deliberately defers it to S-03, and production has one company until signup ships — but the roadmap runs Stream B (S-08 → S-09 → S-11) in parallel with Stream A, and S-11's prerequisites are only S-08/S-09. If S-11 (approval) ships before S-03, a manager can assign an order to another company's technician by id, and sees every company's emails in the picker.
- **Fix A ⭐ Recommended**: Add S-03 as a prerequisite of S-11 in roadmap.md (release gate: no second active company before assignee scoping).
  - Strength: Keeps F-01 scope as planned; the gate lives where slice ordering is decided.
  - Tradeoff: Weakens Stream B parallelism at its last step.
  - Confidence: HIGH — the roadmap's prerequisite column already drives ordering.
  - Blind spot: roadmap.md has uncommitted edits by the user; the edit lands alongside them.
- **Fix B**: Scope the two lookups now with `findByIdAndCompanyId(id, principal.companyId())` and scope `findAssignable` by company.
  - Strength: Removes the leak at the source; principal is already available in OrderController.
  - Tradeoff: Pulls S-03 work into F-01 against the plan's "Not Doing" list; needs its own cross-company tests.
  - Confidence: MED — several call sites and tests in orders/ to update.
  - Blind spot: Haven't checked other order-side account reads S-03 planned to handle together.
- **Decision**: FIXED (Fix A) — roadmap.md: S-11 prerequisites now S-03, S-08, S-09 (table, body, summary); S-11/S-12 removed from S-01–S-03 parallel lists

### F2 — Setup tests depend on no MANAGER existing anywhere in the shared DB

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java:83 (class-level `@Transactional` removed)
- **Detail**: The class now commits for real (needed for the concurrency test), and `/setup` closes on a global `existsByRole(MANAGER)`. Any other test class that leaks a MANAGER row makes the setup tests fail with a misleading redirect-to-login assertion.
- **Fix**: In `@BeforeEach`, assert no MANAGER account exists with a message naming the likely leaking cleanup.
- **Decision**: FIXED — @BeforeEach asserts no MANAGER exists after cleanup, with a message naming the leak

### F3 — V14 guard checks only `accounts`

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/db/migration/V14__create_companies.sql:1-6
- **Detail**: A DB with empty `accounts` but leftover parts/projects/orders passes V14; the later Stream A migrations (NOT NULL `company_id`) would hit those rows. Safe if the deployment.md wipe (all 9 tables) is followed. V14 has not reached `main`, so it can still be edited.
- **Fix**: Extend the guard to raise if any of the 9 application tables has rows.
- **Decision**: FIXED — V14 guard now covers all 9 application tables (message `V14: baza nie jest pusta …`); new `leftoverWarehouseRowsStopMigrationEvenWithoutAccounts` test (break-check: red on the old accounts-only guard); deployment.md wording updated; `V10MigrationTests` now targets "13" like `V4MigrationTests`, since it seeds parts before migrating

### F4 — Blank-company-name test asserts less than the plan's contract

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/regavio/stockahead/account/AuthenticationIntegrationTests.java:124-132
- **Detail**: Asserts email is kept and no account created, but not that `companyName` is kept or that no company was created ("a blank company name creates nothing"). The over-255 test does assert the kept name.
- **Fix**: Add assertions for the kept company name and zero companies with that name.
- **Decision**: FIXED — blank-name test also asserts the submitted `companyName` is kept (no-company-created is guaranteed by the DB CHECK on blank names)

### F5 — Setup and technician forms leak email existence; setup validation is weaker

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/account/TechnicianAccountController.java:103-105; SetupController.java:72-89
- **Detail**: Globally unique email (FR-029) lets company B's manager learn an address is registered elsewhere — inherent and acceptable now, but relevant to S-09 public registration. Pre-existing: `/setup` lacks the technician form's 12-character password minimum and email format check.
- **Fix**: Carry both into S-08/S-09 planning notes (no code change in F-01).
- **Decision**: FIXED — recorded as Unknowns in roadmap.md: S-08 (setup validation) and S-09 (email enumeration, registration validation); no code change

### F6 — `CompanyUser` has no `serialVersionUID`

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/regavio/stockahead/security/CompanyUser.java:14
- **Detail**: Extends `Serializable` `User` without a `serialVersionUID`. Sessions are in-memory today, so no impact.
- **Fix**: Add `private static final long serialVersionUID = 1L;`.
- **Decision**: FIXED — `serialVersionUID = 1L` added

## Triage summary (2026-10-06)

- Fixed: F1 (Fix A), F2, F3, F4, F5, F6
- `./mvnw verify` green after fixes (530+ tests)

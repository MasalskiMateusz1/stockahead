# Frame Brief: Per-company token for manager registration

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

During manual verification of operator-setup-and-company-list (S-08) Phase 3, the only way to get a company and its manager into the system was an SQL insert. Setup now creates only the operator, and nothing in the app creates a company yet.

## Initial Framing (preserved)

- **User's stated cause or approach**: "When setting up the client token should be assigned per company so the manager is automatically assigned to company." A token should decide which company a manager lands in.
- **User's proposed direction**: Issue a token per company; a manager who signs up with it is attached to that company automatically.
- **Pre-dispatch narrowing**: The trigger was (1) no way to create a company without SQL and (2) public sign-up feeling wrong, plus "I don't want a stranger to see each company that has registered". The company itself starts onboarding. Strangers may create a company, with operator approval.

## Dimension Map

1. **Sequencing gap**: S-09 (registration) isn't built yet, so SQL is the only path on this branch. A temporary gap, not a design flaw.
2. **Joining an existing company**: the token assumes a manager must be attached to a company that already exists, either from a visible list or by token.  ← initial framing
3. **Information exposure to strangers**: an anonymous visitor could learn which companies are registered (list, "name taken", "email exists").
4. **Who may create a company**: invitation-only vs open sign-up plus approval.

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. Sequencing gap | S-09 `proposed` (roadmap.md:53). The S-08 plan explicitly accepts "an SQL insert for manual checks" until S-09/S-11 land and rules out a dev seeder (operator-setup-and-company-list/plan.md:40, plan-brief.md:44). Before S-08, setup created company + manager (commit b843c42); aad6a22 moved it to the operator. | STRONG |
| 2. Joining an existing company (token) | FR-023 (prd-v2.md:94): the registrant creates the company **and** becomes its manager in one step. FR-030/FR-032: further accounts are created inside the company, which comes from the principal (CompanyAccountController.java:96). No PRD flow lets an outsider join an existing company; no invite/token concept exists in code or docs (grep over context/** and src/main). The planned design already attaches the manager without a token. | NONE (need not present) |
| 3. Strangers see registered companies | Only `/login`, `/setup/**`, health and static files are public (SecurityConfig.java:35-40). The only cross-company read is `findAllForOperatorList`, OPERATOR-only (OperatorCompanyController.java:23-25). `companies.name` has no unique constraint (V14), so there's no "name taken" leak. Login has no company picker (prd-v2 Access Control). The only leak is **email** enumeration via the global unique email (`accounts.error.duplicateEmail`): an open S-09 unknown (roadmap.md:206), and it already exists between companies on the technician form. | NONE for company names; WEAK (email only) |
| 4. Invitation-only creation | The user chose "strangers, with approval" and "the company itself starts". The PRD's FR-023/FR-026 are open sign-up with approval. | NONE |

## Narrowing Signals

- "Assigned to a company" refers to the **company's founder**, not a colleague joining. FR-023 already creates the company and makes the founder its manager in one step, which rules out dimension 2.
- The stranger-exposure worry was a **company picker** on the sign-up page. No such picker exists or is planned; the founder types a new company name. Dimension 3 is satisfied by design for company names.
- The SQL pain is **only while testing now**, which confirms dimension 1.

## Cross-System Convention

Multi-tenant SaaS conventionally onboards in two ways: a self-sign-up that creates a new tenant with its founder as admin, and in-tenant invitations or admin-created accounts for colleagues. Stockahead's plan already follows this: FR-023 is the first, and FR-030/032 (S-07, done) is the second via admin-created accounts. Per-tenant join tokens are the convention only for joining an *existing* tenant from outside, which the user does not need.

## Reframed Problem Statement

> **The actual problem to plan around is**: on this branch there is no in-app way to create a company and its manager until S-09 (public registration) lands; the manager→company link itself is already solved by the planned design and needs no token.

The per-company token answers a "join an existing company" need that nobody has: the founder creates the company and becomes its manager in one step (FR-023), and colleagues are created from inside the company (S-07). The SQL insert is the deliberate, temporary cost of S-08 moving setup to the operator. It goes away when S-09 is built, so the leverage is in building S-09 next. Two requirements surfaced here belong in S-09's plan: the sign-up form must never list existing companies, and the open "email already exists" enumeration question should be decided there.

## Confidence

- **HIGH**: strong file:line evidence for the sequencing gap and for FR-023 already binding founder→company; the narrowing answers are decisive; this matches multi-tenant convention.

## What Changes for /10x-plan

Don't plan a per-company token change. Plan **S-09 `company-registration`** next (`/10x-plan company-registration`). Carry two inputs into it:
1. No company picker or list on any public page.
2. Resolve the S-09 email-enumeration unknown, e.g. neutral message vs explicit error.

If testing before S-09 hurts too much, the only stopgap considered so far is a test-run-only dev seeder, which S-08 deliberately excluded. That would be a separate, explicit decision.

## References

- Source files: security/SecurityConfig.java:35-40; account/OperatorCompanyController.java:23-25; account/CompanyRepository.java:30-43; account/CompanyAccountController.java:96; account/SetupController.java:31-35; db/migration/V14__create_companies.sql; V4__normalize_account_emails.sql:21
- PRD: context/foundation/prd-v2.md FR-023–FR-026, FR-030, FR-032, FR-033, Access Control Changes, Non-Goals
- Roadmap: context/foundation/roadmap.md S-09 (Unknowns ~l.206), S-10, S-11
- Prior decision: context/changes/operator-setup-and-company-list/plan.md:40 (no dev seeder; SQL for manual checks)

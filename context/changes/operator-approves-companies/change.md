---
change_id: operator-approves-companies
title: Operator approves or rejects pending companies
status: impl_reviewed
created: 2026-11-08
updated: 2026-11-08
---

## Notes

Roadmap S-11. Operator approves (PENDING → ACTIVE) or rejects (PENDING → REJECTED) a registration from the company list; a rejected registration stays on record, its users fail login generically, and its email can register again.

Implementation note: `OperatorCompanyController` approve and reject catch `DataIntegrityViolationException` and flash `operator.companies.actionFailed`. This is a deliberate defensive addition per lessons.md ("Handle DB constraint violations at write boundaries"). No current transition can raise it, so it has no controller test.

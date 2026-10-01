<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Narrow Reservation Locking Implementation Plan

- **Plan**: context/changes/narrow-reservation-locking/plan.md
- **Scope**: Full plan
- **Reviewed phases**: 1, 2, 3
- **Date**: 2026-10-01
- **Verdict**: NEEDS ATTENTION (F1 fixed, F2 skipped during triage — see Decisions)
- **Findings**: 0 critical, 1 warning, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Retry loop's bound and exhaustion path have no deterministic test

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:171-182
- **Detail**: `executeWithLockRetry`'s attempt bound (exactly `MAX_LOCK_RETRY_ATTEMPTS = 3`) and its exhaustion re-throw path are exercised today only by the real-concurrency integration tests (`ReservationConcurrencyTests`, `OrderCreationIntegrationTests`), which assert the end invariant (no over-reservation) but never assert that a retry actually fired or that exhaustion re-throws after exactly 3 attempts. The safety reviewer's analysis of `OrderController.java:143-147` found that `orderRepository.saveAndFlush(order)` takes an implicit Postgres `FOR KEY SHARE` lock on each referenced `Part` row at FK-check time, *before* `reallocateForParts`'s explicit `FOR UPDATE` — when two concurrent transactions share a part, both already hold `FOR KEY SHARE` before escalating, which is Postgres's textbook lock-upgrade deadlock case. `ORDER BY p.id` does not prevent this cross-type escalation deadlock (it only orders acquisitions *within* the `FOR UPDATE` query). This means the retry loop is likely load-bearing for the Phase 3 two-part test to pass reliably, not pure defense-in-depth — but nothing in the test suite pins down that the retry is actually what's saving it. A regression that silently narrowed the caught exception type, or changed the retry bound, could pass CI on a lucky run and go undetected. This also means `context/foundation/lessons.md`'s rule ("verified under both `ReservationAllocatorTests`-style algorithmic tests and `ReservationConcurrencyTests`-style real-concurrency tests") is only half-satisfied — the retry mechanism itself has no algorithmic-style test, only probabilistic concurrency coverage.
- **Fix**: Add a narrow unit test for `executeWithLockRetry` using a stub `Supplier`/counter that throws `PessimisticLockingFailureException` twice then returns successfully — assert exactly 3 invocations occurred and the correct value is returned; add a second test asserting that a `Supplier` which always throws causes exhaustion to re-throw the last failure after exactly `MAX_LOCK_RETRY_ATTEMPTS` attempts.
  - Strength: Deterministic, fast, and directly tests the Phase 2 contract (bound + exhaustion + no-backoff) independent of real Postgres timing — closes the "verified under algorithmic tests" half of the lessons.md rule.
  - Tradeoff: `executeWithLockRetry` is currently private; testing it in isolation requires either package-private visibility, extracting it as a small testable unit, or reflection — a minor structural touch, not a redesign.
  - Confidence: MEDIUM — the fix itself is straightforward, but the right seam (private method test vs. extraction) depends on how `OrderControllerTests` (if one exists) is structured; not independently verified in this review.
  - Blind spot: Haven't confirmed whether an `OrderControllerTests`-style unit test file already exists that this could extend, or whether `OrderController`'s dependencies (project/order repos) are already mockable there.
- **Decision**: FIXED — added `src/test/java/pl/regavio/stockahead/orders/OrderControllerTests.java` (Mockito, `@ExtendWith(MockitoExtension.class)`, same style as `AccountUserDetailsServiceTest`) with `retriesUpToThreeTimesThenSucceeds` and `reThrowsLastFailureAfterExhaustingThreeAttempts`. Widened `OrderController.executeWithLockRetry` from `private` to package-private so the test can call it directly. Full `./mvnw verify` green afterward (both new tests pass, no regressions).

### F2 — Implicit insert-time lock-upgrade deadlock path undocumented

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/regavio/stockahead/orders/OrderController.java:125-147
- **Detail**: As described in F1, `saveAndFlush(order)` implicitly takes `FOR KEY SHARE` on referenced `Part` rows before `reallocateForParts` explicitly takes `FOR UPDATE` on the same rows — a lock-upgrade deadlock shape that `findByIdInForUpdate`'s `ORDER BY p.id` does not, by itself, prevent (it only orders acquisition across *different* `Part` rows, not the FOR KEY SHARE → FOR UPDATE escalation on the *same* row). The retry loop added in Phase 2 correctly absorbs this, but neither the retry wrapper's Javadoc nor `ReservationAllocator`'s class comment calls out that this specific failure mode — not just the two previously-rejected narrowing approaches from `lessons.md` — is part of what the retry defends against. A future reader narrowing or removing the retry (reading only the `lessons.md` entry about the two rejected approaches) could reasonably conclude the ordered single-query design alone is deadlock-proof, which per this finding it is not.
- **Fix**: Add a one/two-line comment on `OrderController.executeWithLockRetry` or at its call site noting that the retry also covers the implicit `FOR KEY SHARE`→`FOR UPDATE` escalation deadlock between the order/line insert and the part lock, not only cross-transaction lock-ordering.
- **Decision**: SKIPPED

# Company-Scoped Orders — Plan Brief

> Full plan: `context/changes/company-scoped-orders/plan.md`
> Frame brief: `context/changes/multi-company-support/frame.md`

## What & Why

This is roadmap item S-03 of milestone M-2. Orders and their reservations stay within one company. A manager orders, changes, cancels, confirms and assigns only their own company's orders, and only to accounts of the same company. Allocation by priority → deadline → age runs over that company's orders and parts only. Two companies never compete for stock or wait on each other's locks, and another company's order id is a 404. This is FR-028 plus prd-v2's per-company stock and reservation guardrail.

## Starting Point

S-01/S-02 scoped parts, projects and BOMs, including the order form's project picker and project lookup. `orders`/`order_lines` have no company. All 8 manager order routes and the `/orders` list use bare lookups. The assignee picker and lookup are global. `ReservationAllocator` separates by part but takes no company.

## Desired End State

Composite FKs make an order on a foreign project, a foreign assignee, or a line on a foreign order or part impossible in the database. B gets 404 on every `/orders/{A}…` route, and A's order is unchanged. Order lists, the picking list and the assignee pickers show only the user's own company. The allocator locks and reads only the given company's parts and lines. A test proves that A holding its part locks doesn't delay B.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Scoping mechanism | Explicit `companyId` parameter, no `@TenantId`/`@Filter` | Same as F-01/S-01/S-02; native SQL and row locks aren't filtered automatically | Frame / AGENTS.md |
| Response to a foreign id | 404 on order routes, `assigneeUnavailable` for an assignee | AGENTS.md: "exactly like a missing id" | AGENTS.md |
| Allocator and shared-query boundary | `reallocateForParts(companyId, partIds)` with a scoped lock, quantities and open lines; order list queries take `companyId`; callers outside orders pass it, but their own bare lookups stay for S-04/S-05 | Satisfies AGENTS.md's allocator rule now without absorbing S-04/S-05 | Plan (user) |
| Stock correction's allocator company | The locked part's company, not the principal's, until S-05 | A still-unscoped foreign correction must not leave the part's owner over-reserved | Plan |
| Assignee ownership | S-03, with composite FK `orders (assignee_id, company_id) → accounts (id, company_id)` | Matches S-11's release gate; the DB refuses a forgotten check | Plan (user) |
| `order_lines` company column | Yes, with composite FKs to its order and part | AGENTS.md lists reservations; a line can never draw on another company's part | AGENTS.md |
| Lock independence proof | New `CrossCompanyAllocationLockTests` plus existing concurrency tests unchanged | Makes "never wait on each other's locks" executable | Plan (user) |
| Phase split | Schema and fixtures first, then scoping and tests | Proven in S-01/S-02 | Plan |

## Scope

**In scope:**
- V17: `company_id` on `orders`/`order_lines`, composite FKs (project, assignee, order, part), `accounts (id, company_id)` key
- `Order`/`OrderLine` company columns; 53 test inserts in 22 files; `CompanyFixtures.cleanUp` deletes orders
- Scoped allocator, `OrderController`, `OrderDetailModel`, list and assignee queries; picking-list pass-through
- `OrderSchemaTests`, `OrdersCompanyIsolationIntegrationTests`, `CrossCompanyAllocationLockTests`; roadmap hand-offs for S-04/S-05/S-11

**Out of scope:**
- Picking by-id routes, `PickingDetailModel`, `reservedQuantitiesByOpenOrder`, `completion_reported_by` (S-04)
- Delivery, correction and picking part lookups by posted id (S-05/S-04); the shopping list (S-06)
- Any change to the allocation rule or lock strategy; UI changes; new 403 tests

## Architecture / Approach

`CompanyUser.companyId()` flows from each controller into the scoped repository methods and `reallocateForParts(companyId, …)`. `Order.setProject` copies the project's company onto the order, and `Order.addLine` copies it onto each line. The composite FKs then make any cross-company reference a constraint violation that the scoped lookups never reach. Bare repository variants stay only where S-04/S-05 callers still use them.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema, entities and test fixtures | V17, company on `Order`/`OrderLine`, every test seed carries a company, `OrderSchemaTests`; green build with unchanged behavior | Large mechanical churn (53 inserts); a missed insert fails on `NOT NULL` |
| 2. Scoped allocator, routes and two-company tests | Scoped allocator and 8 order routes, the lists and the assignee; B→404, assignee and lock-independence tests | A handler left without the principal; a shared query scoped where S-04 expects bare; the grep criterion and compile errors catch most |

**Prerequisites:** S-02 archived (done); Docker running for Testcontainers.
**Estimated effort:** ~2 sessions, one per phase, similar to S-02.

## Open Risks & Assumptions

- V17 assumes empty `orders`/`order_lines`. This holds through the V14 guard, since multi-company work ships from its branch together.
- Until S-05, a delivery with a foreign part id raises that part's stock without reallocating it, so the owner's short open orders on that part (taken or not) miss the units until the next event on that part. That's missed allocation, never over-reservation, and it's noted in the roadmap.
- The picking list becomes company-scoped here without S-04's dedicated tests. The S-03 isolation test covers the list itself.

## Success Criteria (Summary)

- Two companies each order "Board X" from a part "R1" with stock 5, and both orders are fully reserved. Neither sees, opens nor changes the other's order, and a pasted foreign id shows the 404 page.
- Only the user's own company's accounts can be picked or posted as an assignee.
- Company A's held part locks never delay company B's order.

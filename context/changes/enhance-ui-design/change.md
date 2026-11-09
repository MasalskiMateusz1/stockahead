---
change_id: enhance-ui-design
title: Enhance ui design
status: implementing
created: 2026-11-09
updated: 2026-11-09
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-11-09 — Goal (user): "make app look like 21st century app not 20th". User is a little familiar with React. Framework options A–D are in research.md Part 2.
- 2026-11-09 — Decisions (user, via /10x-ui):
  - **Path**: A — stay on Thymeleaf + plain CSS (`static/css/app.css`). No React, no build step, no new dependency.
  - **View**: `orders-list` (`templates/orders-list.html`, `GET /orders`). One view plus global tokens.
  - **Motif / token source**: Linear/Vercel-like (monochrome, one sharp accent, thin borders, compact rows, tight type scale), mapped onto the existing shadcn token names in `app.css`. Extend, do not fork a second palette.
  - Charge list: `charges.md` (C1–C5 plus deferred items). Next: `/10x-plan enhance-ui-design` with phases tokens → orders-list → states → visual gate.

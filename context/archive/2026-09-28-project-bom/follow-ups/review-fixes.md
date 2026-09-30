# Review follow-ups: project-bom

Source: `reviews/impl-review.md` (2026-09-28).

- **F7 — indexes for S-04.** When `production-order-allocation` (S-04) adds queries that look up BOM lines by part or load links per project at volume, add a new migration with `CREATE INDEX ON bom_lines (part_id)` and `CREATE INDEX ON project_links (project_id)`. Do not edit `V5__create_projects.sql` once it is deployed.

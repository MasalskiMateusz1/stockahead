-- Projects and their BOM lines belong to a company. V14 guarantees projects,
-- bom_lines and project_links are empty, so the columns are NOT NULL with no
-- backfill. project_links stays as is: a link is reached only through its project.

ALTER TABLE projects ADD COLUMN company_id BIGINT NOT NULL REFERENCES companies (id) ON DELETE RESTRICT;

-- Project names are unique per company (FR-028). The leading company_id column
-- also serves lookups by company, so no separate projects_company_id_idx.
ALTER TABLE projects DROP CONSTRAINT projects_name_key;
ALTER TABLE projects ADD CONSTRAINT projects_company_name_key UNIQUE (company_id, name);

-- Target of composite FKs: a BOM line (and later an order) carries its project's company.
ALTER TABLE projects ADD CONSTRAINT projects_id_company_key UNIQUE (id, company_id);

-- A BOM line's project and part belong to the line's company, so a line can
-- never link two companies. ON DELETE actions match V5.
ALTER TABLE bom_lines ADD COLUMN company_id BIGINT NOT NULL;
ALTER TABLE bom_lines DROP CONSTRAINT bom_lines_project_id_fkey;
ALTER TABLE bom_lines DROP CONSTRAINT bom_lines_part_id_fkey;
ALTER TABLE bom_lines ADD CONSTRAINT bom_lines_project_company_fkey
	FOREIGN KEY (project_id, company_id) REFERENCES projects (id, company_id) ON DELETE CASCADE;
ALTER TABLE bom_lines ADD CONSTRAINT bom_lines_part_company_fkey
	FOREIGN KEY (part_id, company_id) REFERENCES parts (id, company_id) ON DELETE RESTRICT;

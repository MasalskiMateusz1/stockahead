CREATE UNIQUE INDEX accounts_single_manager_idx ON accounts (role) WHERE role = 'MANAGER';

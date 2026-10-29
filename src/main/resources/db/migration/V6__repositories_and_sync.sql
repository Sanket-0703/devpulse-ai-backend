-- Level 5: repositories from the GitHub App installation, background sync.

-- 1. The same GitHub repository can be visible to several DevPulse users
--    (e.g. an organization repo). Uniqueness is per GitHub account, not global.
-- (V1 named the old constraint automatically.)
ALTER TABLE github_repositories DROP CONSTRAINT ukcwim1ojgdyx604rkgmouphag3;

ALTER TABLE github_repositories
    ADD CONSTRAINT uk_github_repositories_account_repo UNIQUE (github_account_id, github_id);

-- 2. New columns; timestamps become timezone-aware (UTC).
ALTER TABLE github_repositories
    ALTER COLUMN synced_at TYPE TIMESTAMPTZ USING synced_at AT TIME ZONE 'UTC',
    ADD COLUMN pushed_at TIMESTAMPTZ,
    -- Off until the user opts a repository in (reviews start in Level 7).
    ADD COLUMN review_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_github_repositories_account_id ON github_repositories (github_account_id);

-- 3. Sync status per GitHub account, shown in the UI.
ALTER TABLE github_accounts
    ADD COLUMN sync_status VARCHAR(20) NOT NULL DEFAULT 'NEVER',
    ADD COLUMN last_synced_at TIMESTAMPTZ,
    ADD COLUMN sync_error VARCHAR(500);

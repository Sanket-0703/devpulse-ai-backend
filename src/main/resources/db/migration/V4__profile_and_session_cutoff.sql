-- Level 3: profile & settings.

-- 1. Audit timestamps. V1 created these columns but nothing ever filled
--    them. They become timezone-aware (stored in UTC) and are now set
--    automatically by Hibernate (@CreationTimestamp / @UpdateTimestamp on
--    BaseEntity). Existing rows are backfilled with "now", the best we know.

ALTER TABLE users
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE github_accounts
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE github_repositories
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE pull_requests
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

UPDATE users               SET created_at = now() WHERE created_at IS NULL;
UPDATE users               SET updated_at = created_at WHERE updated_at IS NULL;
UPDATE github_accounts     SET created_at = now() WHERE created_at IS NULL;
UPDATE github_accounts     SET updated_at = created_at WHERE updated_at IS NULL;
UPDATE github_repositories SET created_at = now() WHERE created_at IS NULL;
UPDATE github_repositories SET updated_at = created_at WHERE updated_at IS NULL;
UPDATE pull_requests       SET created_at = now() WHERE created_at IS NULL;
UPDATE pull_requests       SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE users               ALTER COLUMN created_at SET NOT NULL, ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE github_accounts     ALTER COLUMN created_at SET NOT NULL, ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE github_repositories ALTER COLUMN created_at SET NOT NULL, ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE pull_requests       ALTER COLUMN created_at SET NOT NULL, ALTER COLUMN updated_at SET NOT NULL;

-- 2. Session cutoff. Access tokens issued before this moment are rejected
--    even if not yet expired. Set on password change and "sign out
--    everywhere", so a stolen 15-minute access token dies immediately too.
ALTER TABLE users ADD COLUMN tokens_valid_after TIMESTAMPTZ;

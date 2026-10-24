-- Level 4: connecting GitHub through the DevPulse GitHub App.

-- 1. GitHub tokens are now encrypted at rest (AES-256-GCM, see
--    TokenEncryptor). Existing rows hold plaintext tokens that SQL can't
--    encrypt, so GitHub data from before Level 4 is removed; users connect
--    GitHub again. (Development data only: there is no production yet.)
DELETE FROM pull_requests;
DELETE FROM github_repositories;
DELETE FROM github_accounts;

-- 2. github_accounts, reshaped for GitHub App user tokens:
--    - refresh_token is optional (GitHub omits it when token expiry is off)
--    - token expiry times become timezone-aware (UTC)
--    - installation_id: the app installation that grants repository access
ALTER TABLE github_accounts
    ALTER COLUMN refresh_token DROP NOT NULL,
    ALTER COLUMN access_token_expires_at  TYPE TIMESTAMPTZ USING access_token_expires_at  AT TIME ZONE 'UTC',
    ALTER COLUMN refresh_token_expires_at TYPE TIMESTAMPTZ USING refresh_token_expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN connected_at             TYPE TIMESTAMPTZ USING connected_at             AT TIME ZONE 'UTC',
    ADD COLUMN installation_id BIGINT;

-- 3. Accounts created with "Sign in with GitHub" have no password until the
--    user sets one in Settings → Security.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

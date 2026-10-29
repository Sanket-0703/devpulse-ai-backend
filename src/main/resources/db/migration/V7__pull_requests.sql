-- Level 6: pull requests, synced per repository with an installation token.

-- Pull requests are a copy of GitHub data, re-created by the next sync.
-- Nothing has written this table since V5 emptied it; start clean so the
-- new NOT NULL columns need no made-up values.
DELETE FROM pull_requests;

-- 1. The same pull request can be visible to several DevPulse users (an
--    organization repo). V1 made github_id globally unique; the real key is
--    (repository row, number), which uk_pull_request_repository_number has.
ALTER TABLE pull_requests DROP CONSTRAINT ukhjvu0eci20kg12cepliigk6q4;

-- 2. State: GitHub says only open/closed; "merged" is a closed PR with
--    merged_at set. Storing MERGED as its own state makes the
--    Open / Merged / Closed filter a plain equality check.
ALTER TABLE pull_requests DROP CONSTRAINT pull_requests_state_check;
ALTER TABLE pull_requests DROP COLUMN merged;
ALTER TABLE pull_requests
    ALTER COLUMN state TYPE VARCHAR(20),
    ADD CONSTRAINT pull_requests_state_check CHECK (state IN ('OPEN', 'CLOSED', 'MERGED'));

-- 3. Descriptions can be up to 65,536 characters on GitHub.
ALTER TABLE pull_requests ALTER COLUMN description TYPE TEXT;

-- 4. Timestamps become timezone-aware (UTC), and new columns.
ALTER TABLE pull_requests
    ALTER COLUMN opened_at TYPE TIMESTAMPTZ USING opened_at AT TIME ZONE 'UTC',
    ALTER COLUMN opened_at SET NOT NULL,
    ALTER COLUMN closed_at TYPE TIMESTAMPTZ USING closed_at AT TIME ZONE 'UTC',
    ALTER COLUMN merged_at TYPE TIMESTAMPTZ USING merged_at AT TIME ZONE 'UTC',
    ALTER COLUMN synced_at TYPE TIMESTAMPTZ USING synced_at AT TIME ZONE 'UTC',
    -- GitHub's updated_at: list order and the incremental sync cutoff.
    ADD COLUMN github_updated_at TIMESTAMPTZ NOT NULL,
    ADD COLUMN draft BOOLEAN NOT NULL DEFAULT FALSE,
    -- The commit the PR currently points at; reviews (Level 7) are per commit.
    ADD COLUMN head_sha VARCHAR(64) NOT NULL,
    ADD COLUMN author_avatar_url VARCHAR(1000);

-- 5. Deleting a repository deletes its pull requests, also outside JPA.
ALTER TABLE pull_requests DROP CONSTRAINT fkf2x1diiur4fqmbivwbhc2sql8;
ALTER TABLE pull_requests
    ADD CONSTRAINT fk_pull_requests_repository
        FOREIGN KEY (github_repository_id) REFERENCES github_repositories (id) ON DELETE CASCADE;

-- The list query: one repository, optionally one state, newest activity first.
CREATE INDEX idx_pull_requests_repository_updated
    ON pull_requests (github_repository_id, github_updated_at DESC);
CREATE INDEX idx_pull_requests_repository_state_updated
    ON pull_requests (github_repository_id, state, github_updated_at DESC);

-- 6. When this repository's pull requests were last synced (null = never).
ALTER TABLE github_repositories ADD COLUMN pull_requests_synced_at TIMESTAMPTZ;

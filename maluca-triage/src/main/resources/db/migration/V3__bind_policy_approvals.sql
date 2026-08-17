CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE policy_proposals
    ADD COLUMN proposal_sha256 varchar(64),
    ADD COLUMN target_policy_sha256 varchar(64);

-- jsonb::text has a canonical key ordering in PostgreSQL. Existing proposals
-- receive the same digest expression used for every new insert and apply-time
-- integrity check.
UPDATE policy_proposals
SET proposal_sha256 = encode(
        digest(convert_to(patch::text, 'UTF8'), 'sha256'),
        'hex');

ALTER TABLE policy_proposals
    ALTER COLUMN proposal_sha256 SET NOT NULL,
    ADD CONSTRAINT policy_proposals_proposal_sha256_check
        CHECK (proposal_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT policy_proposals_policy_sha256_check
        CHECK (policy_sha256 ~ '^[0-9a-fA-F]{64}$'),
    ADD CONSTRAINT policy_proposals_target_policy_sha256_check
        CHECK (target_policy_sha256 IS NULL
            OR target_policy_sha256 ~ '^[0-9a-f]{64}$');

CREATE INDEX policy_proposals_pending_incident_idx
    ON policy_proposals (incident_id, created_at DESC)
    WHERE status IN ('PROPOSED', 'APPROVED');

-- These rows need operator reconciliation and must continue to block a second
-- active incident for the same policy.
DROP INDEX incidents_one_active_policy_idx;
CREATE UNIQUE INDEX incidents_one_active_policy_idx
    ON incidents (policy_name)
    WHERE closed_at IS NULL
      AND status NOT IN ('RESOLVED', 'DISMISSED', 'APPLY_FAILED');

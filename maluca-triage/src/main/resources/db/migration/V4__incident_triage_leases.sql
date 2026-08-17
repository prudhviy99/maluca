ALTER TABLE incidents
    ADD COLUMN triage_lease_id uuid,
    ADD COLUMN triage_claimed_at timestamptz,
    ADD COLUMN triage_attempts integer NOT NULL DEFAULT 0
        CHECK (triage_attempts >= 0),
    ADD COLUMN triage_next_attempt_at timestamptz,
    ADD COLUMN triage_failure text,
    ADD CONSTRAINT incidents_triage_lease_pair_check CHECK (
        (triage_lease_id IS NULL AND triage_claimed_at IS NULL)
        OR (triage_lease_id IS NOT NULL AND triage_claimed_at IS NOT NULL)
    );

CREATE INDEX incidents_triage_queue_idx
    ON incidents (triage_next_attempt_at, opened_at)
    WHERE status = 'OPEN';

CREATE INDEX incidents_triage_lease_idx
    ON incidents (triage_claimed_at)
    WHERE status = 'TRIAGING';

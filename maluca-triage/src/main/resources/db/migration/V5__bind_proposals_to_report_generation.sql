ALTER TABLE policy_proposals
    ADD COLUMN report_created_at timestamptz;

UPDATE policy_proposals AS proposal
   SET report_created_at = report.created_at
  FROM triage_reports AS report
 WHERE proposal.report_id = report.id;

-- Older pending rows were created before exact report-generation provenance
-- existed. They remain visible for audit but cannot be approved safely.
UPDATE policy_proposals
   SET status = 'REJECTED_STALE_PROVENANCE',
       failure = 'proposal predates exact valid-report provenance; create and review a new proposal'
 WHERE status = 'PROPOSED';

ALTER TABLE policy_proposals
    ADD CONSTRAINT policy_proposals_pending_report_provenance_check CHECK (
        status <> 'PROPOSED'
        OR (report_id IS NOT NULL AND report_created_at IS NOT NULL)
    );

CREATE UNIQUE INDEX policy_proposals_report_generation_patch_actor_idx
    ON policy_proposals (report_id, report_created_at, proposal_sha256, created_by)
    WHERE report_id IS NOT NULL AND report_created_at IS NOT NULL;

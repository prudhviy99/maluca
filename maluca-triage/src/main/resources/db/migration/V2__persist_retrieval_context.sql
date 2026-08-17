ALTER TABLE triage_reports
    ADD COLUMN retrieval_context jsonb NOT NULL DEFAULT '[]'::jsonb;

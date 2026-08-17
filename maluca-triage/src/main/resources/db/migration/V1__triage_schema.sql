CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE decisions (
    event_id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    client_key text NOT NULL,
    method varchar(16) NOT NULL,
    path text NOT NULL,
    policy_name varchar(128) NOT NULL,
    policy_route varchar(512) NOT NULL,
    policy_mode varchar(32) NOT NULL,
    tier varchar(64) NOT NULL,
    computed_action varchar(32) NOT NULL,
    executed_action varchar(32) NOT NULL,
    score integer NOT NULL CHECK (score BETWEEN 0 AND 100),
    reason varchar(256) NOT NULL,
    contributions jsonb NOT NULL DEFAULT '{}'::jsonb,
    dry_run boolean NOT NULL,
    trace_id varchar(64)
);

CREATE INDEX decisions_occurred_brin ON decisions USING brin (occurred_at);
CREATE INDEX decisions_policy_time_idx ON decisions (policy_name, occurred_at DESC);
CREATE INDEX decisions_action_time_idx ON decisions (computed_action, occurred_at DESC);
CREATE INDEX decisions_reason_time_idx ON decisions (reason, occurred_at DESC);

CREATE TABLE incidents (
    id uuid PRIMARY KEY,
    opened_at timestamptz NOT NULL,
    closed_at timestamptz,
    last_active_at timestamptz NOT NULL,
    policy_name varchar(128) NOT NULL,
    policy_route varchar(512) NOT NULL,
    trigger varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    stats jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX incidents_one_active_policy_idx
    ON incidents (policy_name)
    WHERE closed_at IS NULL AND status NOT IN ('RESOLVED', 'DISMISSED', 'APPLY_FAILED');
CREATE INDEX incidents_opened_idx ON incidents (opened_at DESC);
CREATE INDEX incidents_status_idx ON incidents (status, opened_at DESC);

CREATE TABLE triage_reports (
    id uuid PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    model varchar(256) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    classification varchar(64) NOT NULL,
    confidence varchar(32) NOT NULL,
    summary text NOT NULL,
    evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    citations jsonb NOT NULL DEFAULT '[]'::jsonb,
    proposed_patch jsonb,
    valid boolean NOT NULL,
    validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    raw_response text NOT NULL,
    UNIQUE (incident_id)
);

CREATE INDEX triage_reports_incident_idx ON triage_reports (incident_id);

CREATE TABLE policy_proposals (
    id uuid PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    report_id uuid REFERENCES triage_reports(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(128) NOT NULL,
    patch jsonb NOT NULL,
    policy_sha256 varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    approved_at timestamptz,
    applied_at timestamptz,
    failure text
);

CREATE INDEX policy_proposals_incident_idx ON policy_proposals (incident_id, created_at DESC);

CREATE TABLE audit_events (
    id bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    incident_id uuid REFERENCES incidents(id) ON DELETE SET NULL,
    actor varchar(128) NOT NULL,
    action varchar(128) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_events_incident_idx ON audit_events (incident_id, occurred_at DESC);

-- Spring AI PgVectorStore canonical shape. Source, heading, stable chunk ID,
-- and checksum live in metadata so the framework remains the vector owner.
CREATE TABLE runbook_chunks (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text NOT NULL,
    metadata json NOT NULL,
    embedding vector(768) NOT NULL
);

CREATE INDEX runbook_chunks_embedding_hnsw_idx
    ON runbook_chunks USING hnsw (embedding vector_cosine_ops);
CREATE UNIQUE INDEX runbook_chunks_chunk_id_idx ON runbook_chunks ((metadata->>'chunk_id'));

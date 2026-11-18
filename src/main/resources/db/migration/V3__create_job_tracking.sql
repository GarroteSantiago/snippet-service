-- Latest job published per snippet and type (contracts/redis-streams.md, rule 4): a result
-- whose jobId isn't the one here is stale and ignored.
CREATE TABLE snippet_job (
    snippet_id  uuid         NOT NULL REFERENCES snippet (id) ON DELETE CASCADE,
    job_type    varchar(20)  NOT NULL CHECK (job_type IN ('VALIDATE', 'LINT', 'FORMAT')),
    job_id      uuid         NOT NULL,
    created_at  timestamptz  NOT NULL,
    PRIMARY KEY (snippet_id, job_type)
);

-- Last `parserVersion` seen from the Runner (US-18). A single row; null until the first check.
CREATE TABLE parser_state (
    id              smallint     PRIMARY KEY CHECK (id = 1),
    parser_version  varchar(50),
    updated_at      timestamptz  NOT NULL
);

INSERT INTO parser_state (id, parser_version, updated_at) VALUES (1, NULL, now());

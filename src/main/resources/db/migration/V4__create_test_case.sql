-- Tests of each snippet (US-08). They go away with their snippet.
CREATE TABLE test_case (
    id               uuid         PRIMARY KEY,
    snippet_id       uuid         NOT NULL REFERENCES snippet (id) ON DELETE CASCADE,
    name             text         NOT NULL,
    -- Inputs in the order the snippet reads them, and the expected output of each println: ["...", ...]
    inputs           jsonb        NOT NULL DEFAULT '[]',
    expected_outputs jsonb        NOT NULL DEFAULT '[]',
    -- Result of the last run (null if it never ran since it was created or edited)
    last_result      varchar(10)
        CHECK (last_result IN ('success', 'fail')),
    last_run_at      timestamptz,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL
);

CREATE INDEX test_case_snippet_id_idx ON test_case (snippet_id);

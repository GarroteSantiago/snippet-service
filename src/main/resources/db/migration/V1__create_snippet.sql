-- Metadata of each snippet. The content lives in the Asset Service and the
-- permissions in the Permission Service, both keyed by the snippet id.
CREATE TABLE snippet (
    id              uuid          PRIMARY KEY,
    name            varchar(120)  NOT NULL,
    description     varchar(1000),
    language        varchar(50)   NOT NULL,
    version         varchar(20)   NOT NULL,
    extension       varchar(20)   NOT NULL,
    -- JWT `sub` of the owner, and the name shown as `author` (taken at creation)
    owner_id        varchar(255)  NOT NULL,
    owner_name      varchar(255)  NOT NULL,
    compliance      varchar(20)   NOT NULL DEFAULT 'pending'
        CHECK (compliance IN ('pending', 'failed', 'not-compliant', 'compliant')),
    -- Violations of the last lint run: [{rule, message, line, column}]
    lint_violations jsonb         NOT NULL DEFAULT '[]',
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL
);

CREATE INDEX snippet_owner_id_idx ON snippet (owner_id);

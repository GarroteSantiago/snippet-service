-- Format and lint rules of each user (US-11, US-14). A user without a row uses the
-- Runner's defaults. The whole list is stored as given: [{name, isActive, value}].
CREATE TABLE user_rules (
    owner_id    varchar(255) NOT NULL,
    rule_type   varchar(10)  NOT NULL CHECK (rule_type IN ('format', 'lint')),
    rules       jsonb        NOT NULL,
    updated_at  timestamptz  NOT NULL,
    PRIMARY KEY (owner_id, rule_type)
);

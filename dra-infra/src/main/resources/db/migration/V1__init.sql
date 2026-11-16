-- M1 §6 data model. Temporal persistence lives in separate databases (temporal / temporal_visibility).

CREATE TABLE research_task (
    id                 CHAR(26)     NOT NULL,
    idempotency_key    VARCHAR(80)  NOT NULL,
    request_hash       CHAR(64)     NOT NULL,
    question           TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    depth              VARCHAR(16)  NOT NULL,
    budget_tokens      BIGINT       NOT NULL DEFAULT 0,
    budget_cost_micros BIGINT       NOT NULL DEFAULT 0,
    used_tokens        BIGINT       NOT NULL DEFAULT 0,
    used_cost_micros   BIGINT       NOT NULL DEFAULT 0,
    workflow_id        VARCHAR(128) NULL,
    run_id             VARCHAR(128) NULL,
    trace_id           CHAR(32)     NULL,
    stop_reason        VARCHAR(32)  NULL,
    error_code         VARCHAR(64)  NULL,
    error_msg          VARCHAR(1024) NULL,
    report_id          CHAR(26)     NULL,
    created_at         DATETIME(3)  NOT NULL,
    started_at         DATETIME(3)  NULL,
    finished_at        DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_idem (idempotency_key),
    KEY idx_task_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE task_plan (
    id                   CHAR(26)    NOT NULL,
    task_id              CHAR(26)    NOT NULL,
    version              INT         NOT NULL,
    outline_json         JSON        NOT NULL,
    stop_conditions_json JSON        NULL,
    model_profile        VARCHAR(32) NULL,
    prompt_version       VARCHAR(16) NULL,
    created_at           DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_task_version (task_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sub_question (
    id              CHAR(26)     NOT NULL,
    task_id         CHAR(26)     NOT NULL,
    plan_version    INT          NOT NULL,
    seq             INT          NOT NULL,
    title           VARCHAR(512) NULL,
    question        TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    coverage_score  DECIMAL(4,3) NULL,
    missing_aspects JSON         NULL,
    PRIMARY KEY (id),
    KEY idx_subq_task_status (task_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE search_query (
    id              CHAR(26)    NOT NULL,
    task_id         CHAR(26)    NOT NULL,
    sub_question_id CHAR(26)    NOT NULL,
    `round`         INT         NOT NULL,
    provider        VARCHAR(32) NOT NULL,
    query_text      TEXT        NOT NULL,
    query_hash      CHAR(64)    NOT NULL,
    status          VARCHAR(16) NOT NULL,
    result_count    INT         NOT NULL DEFAULT 0,
    latency_ms      INT         NOT NULL DEFAULT 0,
    cost_micros     BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_task_round_query (task_id, `round`, query_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE source (
    id           CHAR(26)      NOT NULL,
    task_id      CHAR(26)      NOT NULL,
    url          TEXT          NOT NULL,
    url_hash     CHAR(64)      NOT NULL,
    canonical_url TEXT         NULL,
    domain       VARCHAR(255)  NULL,
    title        VARCHAR(1024) NULL,
    published_at DATETIME(3)   NULL,
    http_status  INT           NULL,
    content_hash CHAR(64)      NULL,
    content_len  INT           NOT NULL DEFAULT 0,
    fetch_status VARCHAR(16)   NOT NULL,
    fetched_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_task_url (task_id, url_hash),
    KEY idx_source_task_domain (task_id, domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE source_content (
    source_id    CHAR(26)    NOT NULL,
    content_gzip MEDIUMBLOB  NOT NULL,
    content_hash CHAR(64)    NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (source_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE evidence (
    id              CHAR(26)     NOT NULL,
    task_id         CHAR(26)     NOT NULL,
    source_id       CHAR(26)     NOT NULL,
    sub_question_id CHAR(26)     NOT NULL,
    `round`         INT          NOT NULL,
    quote           TEXT         NOT NULL,
    quote_hash      CHAR(64)     NOT NULL,
    anchor_json     JSON         NULL,
    origin          VARCHAR(16)  NOT NULL,
    aspect          VARCHAR(256) NULL,
    extract_version VARCHAR(16)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_src_sub_quote (source_id, sub_question_id, quote_hash),
    KEY idx_evidence_task_sub (task_id, sub_question_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE claim (
    id          CHAR(26)     NOT NULL,
    task_id     CHAR(26)     NOT NULL,
    section_key VARCHAR(64)  NOT NULL,
    statement   TEXT         NOT NULL,
    importance  TINYINT      NOT NULL DEFAULT 1,
    status      VARCHAR(16)  NOT NULL,
    confidence  DECIMAL(4,3) NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_claim_task_section (task_id, section_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE citation (
    id           CHAR(26)   NOT NULL,
    claim_id     CHAR(26)   NOT NULL,
    evidence_id  CHAR(26)   NOT NULL,
    support_type VARCHAR(16) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_citation_claim_evidence (claim_id, evidence_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE verification (
    id                CHAR(26)    NOT NULL,
    claim_id          CHAR(26)    NOT NULL,
    `round`           INT         NOT NULL,
    rule_verdict      VARCHAR(16) NOT NULL,
    llm_verdict       VARCHAR(16) NOT NULL,
    final_verdict     VARCHAR(16) NOT NULL,
    rationale         TEXT        NULL,
    checked_source_ids JSON       NULL,
    verifier_profile  VARCHAR(32) NOT NULL,
    prompt_version    VARCHAR(16) NOT NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_claim_round_profile (claim_id, `round`, verifier_profile)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE conflict (
    id              CHAR(26)    NOT NULL,
    task_id         CHAR(26)    NOT NULL,
    claim_group_key VARCHAR(128) NOT NULL,
    summary         TEXT        NULL,
    positions_json  JSON        NULL,
    resolved        TINYINT     NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conflict_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE report (
    id         CHAR(26)    NOT NULL,
    task_id    CHAR(26)    NOT NULL,
    version    INT         NOT NULL,
    markdown   MEDIUMTEXT  NOT NULL,
    meta_json  JSON        NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_task_version (task_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE usage_record (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    task_id           CHAR(26)    NOT NULL,
    activity          VARCHAR(64) NOT NULL,
    model_profile     VARCHAR(32) NULL,
    prompt_tokens     BIGINT      NOT NULL DEFAULT 0,
    completion_tokens BIGINT      NOT NULL DEFAULT 0,
    cost_micros       BIGINT      NOT NULL DEFAULT 0,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_usage_task_activity (task_id, activity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

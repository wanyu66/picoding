CREATE TABLE article_read_outbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    trace_id VARCHAR(128) DEFAULT NULL,
    state TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending MySQL delta, 1=pending Redis projection',
    create_time DATETIME NOT NULL,
    KEY idx_read_outbox_state_time (state, create_time, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable read events and Redis projection retries';

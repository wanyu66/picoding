package com.github.paicoding.forum.service.article.service.search;

/** Search serving state exposed to callers and operations dashboards. */
public enum ArticleSearchState {
    /** Full index is available and requests are served by Elasticsearch. */
    ES_NORMAL,
    /** Index coverage is incomplete; user requests fall back to MySQL title matching. */
    MYSQL_TITLE_FALLBACK,
    /** A new index is being built; the old alias may continue serving until the atomic switch. */
    INDEX_REBUILDING,
    /** ES is disabled, unreachable, timed out, or rejected by the circuit breaker. */
    ES_UNAVAILABLE
}

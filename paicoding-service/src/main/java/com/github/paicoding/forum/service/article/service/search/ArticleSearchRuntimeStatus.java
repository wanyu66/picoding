package com.github.paicoding.forum.service.article.service.search;

import lombok.Data;

import java.util.Date;

@Data
public class ArticleSearchRuntimeStatus {
    private ArticleSearchState state;
    private String message;
    private boolean indexCoverageComplete;
    private boolean rebuildInProgress;
    private boolean circuitOpen;
    private int consecutiveFailures;
    private Date circuitOpenUntil;
    private Date lastFailureTime;
    private String lastFailure;
}

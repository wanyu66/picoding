package com.github.paicoding.forum.service.article.service.search;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 文章搜索索引重建任务的可观测状态。 */
@Data
public class ArticleSearchRebuildTask {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String ABORTED = "ABORTED";

    private volatile String taskId;
    private volatile String status;
    private volatile String targetIndex;
    private volatile long total;
    private volatile long processed;
    private volatile long succeeded;
    private volatile long failed;
    private volatile int progress;
    private volatile boolean abortRequested;
    private volatile Date createTime;
    private volatile Date startTime;
    private volatile Date finishTime;
    private volatile String message;
    private List<FailedBatch> failedBatches = new CopyOnWriteArrayList<>();

    @Data
    public static class FailedBatch {
        private long startExclusive;
        private long endInclusive;
        private int size;
        private int attempts;
        private String error;
    }
}

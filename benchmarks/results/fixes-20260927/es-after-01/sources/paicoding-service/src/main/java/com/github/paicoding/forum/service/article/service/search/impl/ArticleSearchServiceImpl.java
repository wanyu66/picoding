package com.github.paicoding.forum.service.article.service.search.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.enums.PushStatusEnum;
import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSearchSnippetDTO;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import com.github.paicoding.forum.service.article.repository.params.SearchArticleParams;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchResult;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchConsistencyReport;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRebuildTask;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchService;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRuntimeStatus;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchState;
import com.github.paicoding.forum.service.constant.EsFieldConstant;
import com.github.paicoding.forum.service.constant.EsIndexConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Consts;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.apache.http.client.config.RequestConfig;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * 文章全文搜索服务。ES 只负责召回和排序，文章展示数据仍回源 MySQL。
 */
@Slf4j
@Service
public class ArticleSearchServiceImpl implements ArticleSearchService {

    private static final int KEYWORD_BOOTSTRAP_MAX_SIZE = 50;
    private static final long KEYWORD_BOOTSTRAP_DONE_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final long KEYWORD_BOOTSTRAP_LOCK_TTL_SECONDS = 5L * 60;
    private static final String HOT_QUERY_KEY = "article_search:queries:hot";
    private static final String ZERO_RESULT_QUERY_KEY = "article_search:queries:zero";
    private static final String QUERY_COUNT_KEY = "article_search:metrics:query_count";
    private static final String CLICK_COUNT_KEY = "article_search:metrics:click_count";
    private static final String HIGHLIGHT_PRE = "__PAI_HIGHLIGHT_START__";
    private static final String HIGHLIGHT_POST = "__PAI_HIGHLIGHT_END__";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    @Value("${elasticsearch.open:false}")
    private Boolean openES;

    @Value("${elasticsearch.article-index:paicoding_article_v1}")
    private String articleIndex;

    @Value("${elasticsearch.article-alias:paicoding_article}")
    private String articleAlias;

    @Value("${elasticsearch.rebuild-batch-size:500}")
    private int rebuildBatchSize;

    @Value("${elasticsearch.rebuild-max-retries:3}")
    private int rebuildMaxRetries;

    @Value("${elasticsearch.consistency-sample-size:20}")
    private int consistencySampleSize;

    @Value("${elasticsearch.search-max-page:50}")
    private int searchMaxPage;

    @Value("${elasticsearch.search-cache-ttl-seconds:120}")
    private long searchCacheTtlSeconds;

    @Value("${elasticsearch.search-cursor-ttl-seconds:300}")
    private long searchCursorTtlSeconds;

    @Value("${elasticsearch.search-timeout-ms:1500}")
    private int searchTimeoutMs;

    @Value("${elasticsearch.search-connection-request-timeout-ms:200}")
    private int searchConnectionRequestTimeoutMs;

    @Value("${elasticsearch.circuit-failure-threshold:5}")
    private int circuitFailureThreshold;

    @Value("${elasticsearch.circuit-open-seconds:30}")
    private long circuitOpenSeconds;

    @Value("${elasticsearch.slow-query-threshold-ms:500}")
    private long slowQueryThresholdMs;

    @Autowired
    private ObjectProvider<RestHighLevelClient> restHighLevelClientProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Autowired
    private ArticleDao articleDao;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    private final AtomicBoolean indexChecked = new AtomicBoolean(false);
    private final AtomicBoolean indexCoverageChecked = new AtomicBoolean(false);
    private volatile boolean fullIndexAvailable = false;
    private final Set<String> keywordBootstrapInFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, ArticleSearchRebuildTask> rebuildTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> rebuildAbortFlags = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeRebuildTaskId = new AtomicReference<>();
    private final AtomicInteger consecutiveSearchFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong();
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean();
    private final AtomicReference<Date> lastFailureTime = new AtomicReference<>();
    private final AtomicReference<String> lastFailureMessage = new AtomicReference<>();

    @Override
    public boolean enabled() {
        return Boolean.TRUE.equals(openES) && restClient() != null;
    }

    @Override
    public ArticleSearchRuntimeStatus runtimeStatus() {
        ArticleSearchRuntimeStatus status = new ArticleSearchRuntimeStatus();
        boolean circuitOpen = circuitOpenUntilMillis.get() > System.currentTimeMillis();
        boolean rebuilding = rebuildInProgress();
        status.setIndexCoverageComplete(fullIndexAvailable);
        status.setRebuildInProgress(rebuilding);
        status.setCircuitOpen(circuitOpen);
        status.setConsecutiveFailures(consecutiveSearchFailures.get());
        status.setCircuitOpenUntil(circuitOpenUntilMillis.get() <= 0 ? null : new Date(circuitOpenUntilMillis.get()));
        status.setLastFailureTime(lastFailureTime.get());
        status.setLastFailure(lastFailureMessage.get());
        if (!enabled()) {
            status.setState(ArticleSearchState.ES_UNAVAILABLE);
            status.setMessage("Elasticsearch is disabled or no client is available; MySQL title fallback is active");
        } else if (circuitOpen || consecutiveSearchFailures.get() > 0) {
            status.setState(ArticleSearchState.ES_UNAVAILABLE);
            status.setMessage(circuitOpen ? "Elasticsearch circuit breaker is open; MySQL title fallback is active"
                    : "Elasticsearch request failures were detected; MySQL title fallback is active while retries continue");
        } else if (rebuilding) {
            status.setState(ArticleSearchState.INDEX_REBUILDING);
            status.setMessage(fullIndexAvailable ? "new index is rebuilding; the current alias continues serving searches"
                    : "index is rebuilding and coverage is incomplete; MySQL title fallback is active");
        } else if (!fullIndexAvailable) {
            status.setState(ArticleSearchState.MYSQL_TITLE_FALLBACK);
            status.setMessage("Elasticsearch index coverage is incomplete; MySQL title fallback is active");
        } else {
            status.setState(ArticleSearchState.ES_NORMAL);
            status.setMessage("Elasticsearch is serving searches normally");
        }
        return status;
    }

    @Override
    public ArticleSearchResult searchHintArticleIds(String keyword, int limit) {
        if (StringUtils.isBlank(keyword)) {
            return emptyResult();
        }
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(articleKeywordQuery(keyword.trim(), EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE));
        List<Map<String, Object>> filters = onlineFilters();
        return search(keyword.trim(), 1,
                buildSearchBody(0, limit, must, filters, EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE));
    }

    @Async
    @Override
    public void syncHintKeyword(String keyword, int limit) {
        syncKeyword(keyword, normalizePageSize(limit), false, true);
    }

    @Override
    public ArticleSearchResult searchOnlineArticleIds(String keyword, PageParam pageParam) {
        if (StringUtils.isBlank(keyword)) {
            return emptyResult();
        }
        long offset = pageParam == null ? 0 : pageParam.getOffset();
        int size = normalizePageSize(pageParam == null ? PageParam.DEFAULT_PAGE_SIZE : pageParam.getPageSize());
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(articleKeywordQuery(keyword.trim(),
                EsFieldConstant.ES_FIELD_TITLE,
                EsFieldConstant.ES_FIELD_SHORT_TITLE,
                EsFieldConstant.ES_FIELD_SUMMARY,
                EsFieldConstant.ES_FIELD_CONTENT));
        long pageNum = normalizedPageNumber(pageParam, size);
        return search(keyword.trim(), pageNum,
                buildSearchBody(offset, size, must, onlineFilters(),
                        EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE,
                        EsFieldConstant.ES_FIELD_SUMMARY, EsFieldConstant.ES_FIELD_CONTENT));
    }

    @Override
    public List<String> hotQueries(int limit) {
        return rankedQueries(HOT_QUERY_KEY, null, limit);
    }

    @Override
    public List<String> suggestQueries(String prefix, int limit) {
        return rankedQueries(HOT_QUERY_KEY, StringUtils.trimToEmpty(prefix).toLowerCase(Locale.ROOT), limit);
    }

    @Override
    public List<String> zeroResultQueries(int limit) {
        return rankedQueries(ZERO_RESULT_QUERY_KEY, null, limit);
    }

    @Override
    public void recordSearchClick(String keyword, Long articleId) {
        String normalized = normalizeQuery(keyword);
        if (StringUtils.isBlank(normalized) || articleId == null) {
            return;
        }
        try {
            RedisClient.hIncr(CLICK_COUNT_KEY, normalized, 1);
            RedisClient.hIncr("article_search:metrics:click_articles", String.valueOf(articleId), 1);
        } catch (Exception e) {
            log.debug("failed to record article search click", e);
        }
    }

    private List<String> rankedQueries(String key, String prefix, int limit) {
        int normalizedLimit = Math.min(Math.max(limit, 1), 50);
        try {
            return RedisClient.zTopNScore(key, Math.max(normalizedLimit * 5, 50)).stream()
                    .map(ImmutablePair::getLeft)
                    .filter(query -> StringUtils.isBlank(prefix) || query.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(normalizedLimit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("failed to load ranked search queries: key={}", key, e);
            return Collections.emptyList();
        }
    }

    @Async
    @Override
    public void syncOnlineKeyword(String keyword, PageParam pageParam) {
        if (StringUtils.isBlank(keyword)) {
            return;
        }
        long offset = pageParam == null ? 0 : pageParam.getOffset();
        int size = normalizePageSize(pageParam == null ? PageParam.DEFAULT_PAGE_SIZE : pageParam.getPageSize());
        syncKeyword(keyword.trim(), keywordBootstrapSize(offset, size), true, true);
    }

    @Override
    public ArticleSearchResult searchAdminArticleIds(SearchArticleParams params) {
        if (params == null || StringUtils.isBlank(params.getKeyword())) {
            return emptyResult();
        }
        if (hasAdminStructuredFilters(params)) {
            return null;
        }

        long pageNum = params.getPageNum() <= 0 ? PageParam.DEFAULT_PAGE_NUM : params.getPageNum();
        long pageSize = params.getPageSize() <= 0 ? PageParam.DEFAULT_PAGE_SIZE : params.getPageSize();
        long offset = (pageNum - 1) * pageSize;

        List<Map<String, Object>> must = new ArrayList<>();
        must.add(articleKeywordQuery(params.getKeyword().trim(),
                EsFieldConstant.ES_FIELD_TITLE,
                EsFieldConstant.ES_FIELD_SHORT_TITLE,
                EsFieldConstant.ES_FIELD_SUMMARY,
                EsFieldConstant.ES_FIELD_CONTENT));
        if (StringUtils.isNotBlank(params.getTitle())) {
            must.add(multiMatch(params.getTitle().trim(), EsFieldConstant.ES_FIELD_TITLE + "^4", EsFieldConstant.ES_FIELD_SHORT_TITLE + "^3"));
        }
        if (StringUtils.isNotBlank(params.getUserName())) {
            must.add(matchPhrase("authorName", params.getUserName().trim()));
        }

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(term("deleted", YesOrNoEnum.NO.getCode()));
        filters.add(term("status", PushStatusEnum.ONLINE.getCode()));
        addTermFilter(filters, "articleId", params.getArticleId());
        addTermFilter(filters, "authorId", params.getUserId());
        addEffectiveTermFilter(filters, "officalStat", params.getOfficalStat());
        addEffectiveTermFilter(filters, "toppingStat", params.getToppingStat());
        addEffectiveTermFilter(filters, "columnIds", params.getColumnId());
        if (StringUtils.isNotBlank(params.getUrlSlug())) {
            filters.add(term("urlSlug", params.getUrlSlug().trim()));
        }

        int size = normalizePageSize(pageSize);
        return search(params.getKeyword().trim(), pageNum,
                buildSearchBody(offset, size, must, filters,
                        EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE,
                        EsFieldConstant.ES_FIELD_SUMMARY, EsFieldConstant.ES_FIELD_CONTENT));
    }

    @Async
    @Override
    public void syncAdminKeyword(SearchArticleParams params) {
        if (params == null || StringUtils.isBlank(params.getKeyword())) {
            return;
        }
        long pageNum = params.getPageNum() <= 0 ? PageParam.DEFAULT_PAGE_NUM : params.getPageNum();
        long pageSize = params.getPageSize() <= 0 ? PageParam.DEFAULT_PAGE_SIZE : params.getPageSize();
        long offset = (pageNum - 1) * pageSize;
        syncKeyword(params.getKeyword().trim(), keywordBootstrapSize(offset, normalizePageSize(pageSize)), true, true);
    }

    @Override
    public ArticleSearchRebuildTask rebuildArticleIndex() {
        if (!enabled()) {
            throw new IllegalStateException("Elasticsearch article search is disabled");
        }
        String runningTaskId = activeRebuildTaskId.get();
        if (runningTaskId != null) {
            ArticleSearchRebuildTask running = rebuildTasks.get(runningTaskId);
            if (running != null && (ArticleSearchRebuildTask.PENDING.equals(running.getStatus())
                    || ArticleSearchRebuildTask.RUNNING.equals(running.getStatus()))) {
                return running;
            }
        }

        ArticleSearchRebuildTask task = new ArticleSearchRebuildTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setStatus(ArticleSearchRebuildTask.PENDING);
        task.setTargetIndex(versionedIndexName());
        task.setCreateTime(new Date());
        task.setMessage("waiting to rebuild article index");
        if (!activeRebuildTaskId.compareAndSet(runningTaskId, task.getTaskId())) {
            return rebuildTasks.get(activeRebuildTaskId.get());
        }
        rebuildTasks.put(task.getTaskId(), task);
        rebuildAbortFlags.put(task.getTaskId(), new AtomicBoolean(false));
        taskExecutor.execute(() -> executeFullRebuild(task));
        return task;
    }

    @Override
    public ArticleSearchRebuildTask getRebuildTask(String taskId) {
        return rebuildTasks.get(taskId);
    }

    @Override
    public ArticleSearchRebuildTask retryRebuildTask(String taskId) {
        ArticleSearchRebuildTask task = requireTask(taskId);
        if (!ArticleSearchRebuildTask.FAILED.equals(task.getStatus()) || task.getFailedBatches().isEmpty()) {
            return task;
        }
        if (!activeRebuildTaskId.compareAndSet(null, taskId)) {
            return rebuildTasks.get(activeRebuildTaskId.get());
        }
        task.setStatus(ArticleSearchRebuildTask.PENDING);
        task.setAbortRequested(false);
        rebuildAbortFlags.put(taskId, new AtomicBoolean(false));
        taskExecutor.execute(() -> executeFailedBatchRetry(task));
        return task;
    }

    @Override
    public ArticleSearchRebuildTask abortRebuildTask(String taskId) {
        ArticleSearchRebuildTask task = requireTask(taskId);
        AtomicBoolean abort = rebuildAbortFlags.get(taskId);
        if (abort != null && (ArticleSearchRebuildTask.PENDING.equals(task.getStatus())
                || ArticleSearchRebuildTask.RUNNING.equals(task.getStatus()))) {
            abort.set(true);
            task.setAbortRequested(true);
            task.setMessage("abort requested");
        }
        return task;
    }

    @Override
    public ArticleSearchConsistencyReport verifyConsistency() {
        ArticleSearchConsistencyReport report = new ArticleSearchConsistencyReport();
        report.setCheckTime(new Date());
        if (!enabled()) {
            report.setConsistent(false);
            report.setMessage("Elasticsearch article search is disabled");
            return report;
        }
        try {
            if (!ensureIndex()) {
                throw new IOException("article search alias is unavailable");
            }
            report.setMysqlCount(defaultLong(articleDao.countSearchableArticles()));
            report.setElasticsearchCount(countIndexDocuments());
            report.setMysqlMaxUpdateTime(articleDao.maxSearchableArticleUpdateTime());
            report.setElasticsearchMaxUpdateTime(queryIndexMaxUpdateTime());
            List<ArticleSearchDocumentDTO> samples = sampleDocuments(Math.max(0, consistencySampleSize));
            report.setSampleSize(samples.size());
            report.setMismatchedArticleIds(findMismatchedSamples(samples));
            boolean timeMatches = sameTime(report.getMysqlMaxUpdateTime(), report.getElasticsearchMaxUpdateTime());
            report.setConsistent(report.getMysqlCount() == report.getElasticsearchCount()
                    && timeMatches && report.getMismatchedArticleIds().isEmpty());
            report.setMessage(report.isConsistent() ? "MySQL and Elasticsearch are consistent"
                    : "count, latest update time, or sampled content differs");
        } catch (Exception e) {
            report.setConsistent(false);
            report.setMessage(e.getMessage());
            log.warn("failed to verify article search consistency", e);
        }
        return report;
    }

    @Override
    @Scheduled(cron = "${elasticsearch.consistency-check-cron:0 30 3 * * ?}")
    public void scheduledConsistencyCheck() {
        if (!enabled()) {
            return;
        }
        ArticleSearchConsistencyReport report = verifyConsistency();
        if (report.isConsistent()) {
            log.info("article search consistency check passed: mysqlCount={}, esCount={}, samples={}",
                    report.getMysqlCount(), report.getElasticsearchCount(), report.getSampleSize());
        } else {
            log.warn("article search consistency check failed: mysqlCount={}, esCount={}, mysqlUpdate={}, esUpdate={}, mismatches={}, message={}",
                    report.getMysqlCount(), report.getElasticsearchCount(), report.getMysqlMaxUpdateTime(),
                    report.getElasticsearchMaxUpdateTime(), report.getMismatchedArticleIds(), report.getMessage());
        }
    }

    private Date queryIndexMaxUpdateTime() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 1);
        body.put("_source", Collections.singletonList("updateTime"));
        body.put("sort", Collections.singletonList(Collections.singletonMap("updateTime", "desc")));
        Request request = new Request("GET", "/" + indexName() + "/_search");
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        Response response = restClient().performRequest(request);
        Map<String, Object> root = objectMapper.readValue(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), MAP_TYPE);
        List<Map<String, Object>> hits = castList(castMap(root.get("hits")).get("hits"));
        if (hits.isEmpty()) {
            return null;
        }
        return asDate(castMap(hits.get(0).get("_source")).get("updateTime"));
    }

    private List<ArticleSearchDocumentDTO> sampleDocuments(int sampleSize) {
        if (sampleSize <= 0) {
            return Collections.emptyList();
        }
        List<ArticleSearchDocumentDTO> samples = new ArrayList<>(sampleSize);
        long maxId = defaultLong(articleDao.maxSearchableArticleId());
        if (maxId <= 0) {
            return samples;
        }
        Set<Long> sampledIds = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
        for (int i = 0; i < sampleSize; i++) {
            long probe = i * maxId / sampleSize;
            List<ArticleSearchDocumentDTO> next = articleDao.listArticleSearchDocumentsAfterId(probe, 1);
            if (next != null && !next.isEmpty() && sampledIds.add(next.get(0).getArticleId())) {
                samples.add(next.get(0));
            }
        }
        return samples;
    }

    private List<Long> findMismatchedSamples(List<ArticleSearchDocumentDTO> samples) throws IOException {
        if (samples.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        for (ArticleSearchDocumentDTO sample : samples) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_index", indexName());
            doc.put("_id", String.valueOf(sample.getArticleId()));
            docs.add(doc);
        }
        Request request = new Request("POST", "/_mget");
        request.setJsonEntity(objectMapper.writeValueAsString(Collections.singletonMap("docs", docs)));
        Response response = restClient().performRequest(request);
        Map<String, Object> root = objectMapper.readValue(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), MAP_TYPE);
        List<Map<String, Object>> results = castList(root.get("docs"));
        Map<Long, Map<String, Object>> sources = new HashMap<>();
        for (Map<String, Object> result : results) {
            if (Boolean.TRUE.equals(result.get("found"))) {
                sources.put(asLong(result.get("_id")), castMap(result.get("_source")));
            }
        }
        List<Long> mismatches = new ArrayList<>();
        for (ArticleSearchDocumentDTO sample : samples) {
            if (!sourceMatches(sample, sources.get(sample.getArticleId()))) {
                mismatches.add(sample.getArticleId());
            }
        }
        return mismatches;
    }

    private boolean sourceMatches(ArticleSearchDocumentDTO document, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        Map<String, Object> expected = toIndexDocument(document);
        String[] fields = {"articleId", "authorId", "authorName", "title", "shortTitle", "urlSlug",
                "summary", "content", "status", "officalStat", "toppingStat", "deleted"};
        for (String field : fields) {
            if (!Objects.equals(String.valueOf(expected.get(field)), String.valueOf(source.get(field)))) {
                return false;
            }
        }
        return sameTime(document.getUpdateTime(), asDate(source.get("updateTime")));
    }

    private Date asDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, Date.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean sameTime(Date left, Date right) {
        return left == null ? right == null : right != null && left.getTime() == right.getTime();
    }

    @Override
    public void syncArticle(Long articleId) {
        if (!enabled() || articleId == null) {
            return;
        }
        try {
            if (!ensureIndex()) {
                throw new IllegalStateException("article search index is unavailable");
            }
            ArticleSearchDocumentDTO document = articleDao.queryArticleSearchDocument(articleId);
            if (document == null
                    || Objects.equals(YesOrNoEnum.YES.getCode(), document.getDeleted())
                    || !Objects.equals(PushStatusEnum.ONLINE.getCode(), document.getStatus())) {
                deleteArticle(articleId);
                return;
            }
            indexOne(document);
            String rebuildingIndex = activeRebuildTarget();
            if (rebuildingIndex != null) {
                indexOne(document, rebuildingIndex);
            }
        } catch (Exception e) {
            log.warn("failed to sync article search index, articleId={}", articleId, e);
            // The event consumer must roll back its consume marker and retry failed index writes.
            throw new IllegalStateException("failed to sync article search index, articleId=" + articleId, e);
        }
    }

    @Override
    public void deleteArticle(Long articleId) {
        if (!enabled() || articleId == null) {
            return;
        }
        try {
            ArticleSearchDocumentDTO document = articleDao.queryArticleSearchDocument(articleId);
            long version = documentVersion(document);
            deleteDocument(indexName(), articleId, version);
            String rebuildingIndex = activeRebuildTarget();
            if (rebuildingIndex != null) {
                deleteDocument(rebuildingIndex, articleId, version);
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                log.warn("failed to delete article search document, articleId={}", articleId, e);
                throw new IllegalStateException("failed to delete article search document, articleId=" + articleId, e);
            }
        } catch (Exception e) {
            log.warn("failed to delete article search document, articleId={}", articleId, e);
            throw new IllegalStateException("failed to delete article search document, articleId=" + articleId, e);
        }
    }

    private ArticleSearchResult search(String keyword, long pageNum, Map<String, Object> body) {
        if (!enabled()) {
            recordFallback("es_unavailable");
            return null;
        }
        if (!allowSearchRequest()) {
            recordFallback("circuit_open");
            return null;
        }
        if (pageNum < 1 || pageNum > Math.max(1, searchMaxPage)) {
            log.info("article search page rejected: page={}, maxPage={}", pageNum, searchMaxPage);
            releaseHalfOpenProbe();
            return emptyResult();
        }
        try {
            if (!ensureIndex()) {
                onSearchFailure(new IOException("article search index is unavailable"));
                recordFallback("es_unavailable");
                return null;
            }
            if (isFullIndexAvailable()) {
                ArticleSearchResult result = executeSearchPage(keyword, pageNum, body);
                result.setState(rebuildInProgress() ? ArticleSearchState.INDEX_REBUILDING : ArticleSearchState.ES_NORMAL);
                onSearchSuccess();
                recordQueryOutcome(keyword, result);
                return result;
            }
            onSearchSuccess();
            recordFallback(rebuildInProgress() ? "index_rebuilding" : "index_incomplete");
            return null;
        } catch (Exception e) {
            onSearchFailure(e);
            recordFallback("es_failure");
            log.warn("failed to search article index: {}", indexName(), e);
            return null;
        }
    }

    private ArticleSearchResult executeSearchPage(String keyword, long pageNum, Map<String, Object> body) throws IOException {
        String cacheKey = searchResultCacheKey(keyword, body);
        if (pageNum == 1) {
            ArticleSearchResult cached = readCachedResult(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Object> cursor = pageNum <= 1 ? Collections.emptyList() : readCursor(keyword, body, pageNum);
        if (pageNum > 1 && cursor.isEmpty()) {
            recordFallback("cursor_missing");
            return emptyResult();
        }
        if (!cursor.isEmpty()) {
            body.put("search_after", cursor);
        }
        ArticleSearchResult result = executeSearch(keyword, body);
        if (!result.getNextSearchAfter().isEmpty()) {
            writeCursor(keyword, body, pageNum + 1, result.getNextSearchAfter());
        }
        if (pageNum == 1) {
            writeCachedResult(cacheKey, result);
        }
        return result;
    }

    private ArticleSearchResult executeSearch(String keyword, Map<String, Object> body) throws IOException {
        Request request = new Request("GET", "/" + indexName() + "/_search");
        request.setOptions(searchRequestOptions());
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            Response response = restClient().performRequest(request);
            return parseSearchResponse(keyword, EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            outcome = "failure";
            throw e;
        } finally {
            recordEsQuery(keyword, System.nanoTime() - startNanos, outcome);
        }
    }

    private void syncKeyword(String keyword, int size, boolean includeBody, boolean onlineOnly) {
        try {
            if (!enabled() || !allowSearchRequest()) {
                return;
            }
            if (!ensureIndex()) {
                onSearchFailure(new IOException("article search index is unavailable"));
                return;
            }
            bootstrapKeywordDocumentsOnce(keyword, size, includeBody, onlineOnly);
            onSearchSuccess();
        } catch (Exception e) {
            onSearchFailure(e);
            log.warn("failed to bootstrap article search index by keyword: index={}, keyword={}", indexName(), keyword, e);
        }
    }

    private ArticleSearchResult parseSearchResponse(String keyword, String responseBody) throws IOException {
        Map<String, Object> root = objectMapper.readValue(responseBody, MAP_TYPE);
        // HTTP 200 may contain incomplete hits. Use the existing failure/fallback path before caching them.
        if (Boolean.TRUE.equals(root.get("timed_out"))) {
            throw new IOException("article search response timed_out=true");
        }
        Long failedShards = asLong(castMap(root.get("_shards")).get("failed"));
        if (failedShards != null && failedShards > 0) {
            throw new IOException("article search response contains failed shards: " + failedShards);
        }
        Map<String, Object> hitsNode = castMap(root.get("hits"));
        List<Map<String, Object>> hits = castList(hitsNode.get("hits"));

        ArticleSearchResult result = new ArticleSearchResult();
        List<Long> articleIds = new ArrayList<>();
        Map<Long, String> highlights = new HashMap<>();
        Map<Long, List<ArticleSearchSnippetDTO>> snippets = new HashMap<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> source = castMap(hit.get("_source"));
            Long articleId = asLong(source.get("articleId"));
            if (articleId == null) {
                articleId = asLong(hit.get("_id"));
            }
            if (articleId == null) {
                continue;
            }
            articleIds.add(articleId);
            List<ArticleSearchSnippetDTO> hitSnippets = highlightSnippets(castMap(hit.get("highlight")));
            ArticleSearchSnippetDTO exactSnippet = exactKeywordSnippet(keyword, source);
            if (exactSnippet != null) {
                hitSnippets.removeIf(snippet -> !containsKeyword(snippet.getFragment(), keyword));
                hitSnippets.add(0, exactSnippet);
            }
            if (hitSnippets.isEmpty() && exactSnippet != null) {
                hitSnippets.add(exactSnippet);
            }
            if (!hitSnippets.isEmpty()) {
                snippets.put(articleId, hitSnippets);
                highlights.put(articleId, hitSnippets.get(0).getFragment());
            }
        }
        result.setArticleIds(articleIds);
        result.setHighlights(highlights);
        result.setSnippets(snippets);
        result.setTotal(parseTotal(hitsNode.get("total")));
        if (!hits.isEmpty()) {
            Object sort = hits.get(hits.size() - 1).get("sort");
            if (sort instanceof List) {
                result.setNextSearchAfter(new ArrayList<>((List<?>) sort));
            }
        }
        return result;
    }

    private ArticleSearchSnippetDTO exactKeywordSnippet(String keyword, Map<String, Object> source) {
        if (StringUtils.isBlank(keyword) || source == null || source.isEmpty()) {
            return null;
        }
        String[] fields = {EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE,
                EsFieldConstant.ES_FIELD_SUMMARY, EsFieldConstant.ES_FIELD_CONTENT};
        for (String field : fields) {
            Object value = source.get(field);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
            int index = text.indexOf(keyword);
            if (index < 0) {
                continue;
            }
            int start = Math.max(0, index - 24);
            int end = Math.min(text.length(), index + keyword.length() + 72);
            String fragment = (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
            return buildSnippet(field, emphasizeEscapedKeyword(fragment, keyword));
        }
        return null;
    }

    private boolean containsKeyword(String text, String keyword) {
        return StringUtils.isNotBlank(text) && StringUtils.isNotBlank(keyword) && text.contains(keyword.trim());
    }

    private long parseTotal(Object total) {
        if (total instanceof Number) {
            return ((Number) total).longValue();
        }
        if (total instanceof Map) {
            Object value = ((Map<?, ?>) total).get("value");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        }
        return 0L;
    }

    private List<ArticleSearchSnippetDTO> highlightSnippets(Map<String, Object> highlight) {
        List<ArticleSearchSnippetDTO> snippets = new ArrayList<>();
        if (highlight == null || highlight.isEmpty()) {
            return snippets;
        }
        String[] fields = {EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE, EsFieldConstant.ES_FIELD_SUMMARY, EsFieldConstant.ES_FIELD_CONTENT};
        for (String field : fields) {
            Object values = highlight.get(field);
            if (!(values instanceof List)) {
                continue;
            }
            for (Object value : (List<?>) values) {
                if (value == null) {
                    continue;
                }
                String fragment = safeHighlightFragment(String.valueOf(value).replaceAll("\\s+", " ").trim());
                if (StringUtils.isNotBlank(fragment)) {
                    snippets.add(buildSnippet(field, fragment));
                }
            }
        }
        return snippets;
    }

    private ArticleSearchSnippetDTO buildSnippet(String field, String fragment) {
        ArticleSearchSnippetDTO snippet = new ArticleSearchSnippetDTO();
        snippet.setField(field);
        snippet.setFieldName(fieldName(field));
        snippet.setFragment(fragment);
        return snippet;
    }

    private String fieldName(String field) {
        if (EsFieldConstant.ES_FIELD_TITLE.equals(field)) {
            return "标题";
        }
        if (EsFieldConstant.ES_FIELD_SHORT_TITLE.equals(field)) {
            return "教程名";
        }
        if (EsFieldConstant.ES_FIELD_SUMMARY.equals(field)) {
            return "摘要";
        }
        if (EsFieldConstant.ES_FIELD_CONTENT.equals(field)) {
            return "正文";
        }
        return "命中";
    }

    private Map<String, Object> buildSearchBody(long offset, int size, List<Map<String, Object>> must,
                                                List<Map<String, Object>> filters, String... highlightFields) {
        Map<String, Object> bool = new LinkedHashMap<>();
        if (must != null && !must.isEmpty()) {
            bool.put("must", must);
        }
        if (filters != null && !filters.isEmpty()) {
            bool.put("filter", filters);
        }

        Map<String, Object> query = functionScoreQuery(bool);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("track_total_hits", true);
        body.put("query", query);
        List<String> sourceFields = new ArrayList<>();
        Collections.addAll(sourceFields, "articleId", EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SHORT_TITLE,
                EsFieldConstant.ES_FIELD_SUMMARY, EsFieldConstant.ES_FIELD_CONTENT);
        body.put("_source", sourceFields);
        body.put("sort", relevanceSort());
        body.put("highlight", highlight(highlightFields));
        return body;
    }

    private Map<String, Object> functionScoreQuery(Map<String, Object> bool) {
        List<Map<String, Object>> functions = new ArrayList<>();
        functions.add(weightedFieldValue("qualityScore", "sqrt", 1.0d, 0.0d, 2.0d));
        functions.add(weightedFieldValue("readCount", "log1p", 0.08d, 0.0d, 0.8d));
        functions.add(weightedFieldValue("praiseCount", "log1p", 0.3d, 0.0d, 1.2d));
        Map<String, Object> gaussParams = new LinkedHashMap<>();
        gaussParams.put("origin", "now");
        gaussParams.put("scale", "30d");
        gaussParams.put("offset", "7d");
        gaussParams.put("decay", 0.5d);
        Map<String, Object> decay = new LinkedHashMap<>();
        decay.put("gauss", Collections.singletonMap("createTime", gaussParams));
        decay.put("weight", 1.5d);
        functions.add(decay);
        Map<String, Object> functionScore = new LinkedHashMap<>();
        functionScore.put("query", Collections.singletonMap("bool", bool));
        functionScore.put("functions", functions);
        functionScore.put("score_mode", "sum");
        functionScore.put("boost_mode", "sum");
        functionScore.put("max_boost", 20);
        return Collections.singletonMap("function_score", functionScore);
    }

    private Map<String, Object> weightedFieldValue(String field, String modifier, double factor,
                                                    double missing, double weight) {
        Map<String, Object> fieldValue = new LinkedHashMap<>();
        fieldValue.put("field", field);
        fieldValue.put("modifier", modifier);
        fieldValue.put("factor", factor);
        fieldValue.put("missing", missing);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("field_value_factor", fieldValue);
        function.put("weight", weight);
        return function;
    }

    private List<Map<String, Object>> relevanceSort() {
        List<Map<String, Object>> sort = new ArrayList<>();
        sort.add(scoreSort());
        sort.add(sortField("createTime", "date"));
        sort.add(sortField("articleId", "long"));
        return sort;
    }

    private Map<String, Object> scoreSort() {
        return Collections.singletonMap("_score", Collections.singletonMap("order", "desc"));
    }

    private Map<String, Object> sortField(String field, String unmappedType) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order", "desc");
        order.put("unmapped_type", unmappedType);
        return Collections.singletonMap(field, order);
    }

    private Map<String, Object> highlight(String... fields) {
        Map<String, Object> highlight = new LinkedHashMap<>();
        highlight.put("pre_tags", Collections.singletonList(HIGHLIGHT_PRE));
        highlight.put("post_tags", Collections.singletonList(HIGHLIGHT_POST));
        highlight.put("fragment_size", 160);
        highlight.put("number_of_fragments", 3);

        Map<String, Object> fieldMap = new LinkedHashMap<>();
        for (String field : fields) {
            fieldMap.put(field, Collections.emptyMap());
        }
        highlight.put("fields", fieldMap);
        return highlight;
    }

    private List<Map<String, Object>> onlineFilters() {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(term("deleted", YesOrNoEnum.NO.getCode()));
        filters.add(term("status", PushStatusEnum.ONLINE.getCode()));
        return filters;
    }

    private Map<String, Object> multiMatch(String keyword, String... fields) {
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", keyword);
        List<String> fieldList = new ArrayList<>();
        Collections.addAll(fieldList, fields);
        multiMatch.put("fields", fieldList);
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "and");
        return Collections.singletonMap("multi_match", multiMatch);
    }

    private Map<String, Object> articleKeywordQuery(String keyword, String... fields) {
        List<Map<String, Object>> should = new ArrayList<>();
        for (String field : fields) {
            float boost = fieldBoost(field);
            should.add(matchPhrase(field, keyword, boost * 2.0f));
            should.add(matchPhrasePrefix(field, keyword, boost * 1.4f));
            should.add(fuzzyMatch(field, keyword, boost));
            should.add(pinyinMatch(field + ".pinyin", keyword, boost * 0.8f));
            should.add(wildcardContains(exactField(field), keyword, boost * 0.35f));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", should);
        bool.put("minimum_should_match", 1);
        return Collections.singletonMap("bool", bool);
    }

    private String exactField(String field) {
        if (EsFieldConstant.ES_FIELD_TITLE.equals(field)) {
            return EsFieldConstant.ES_FIELD_TITLE_EXACT;
        }
        if (EsFieldConstant.ES_FIELD_SHORT_TITLE.equals(field)) {
            return EsFieldConstant.ES_FIELD_SHORT_TITLE_EXACT;
        }
        if (EsFieldConstant.ES_FIELD_SUMMARY.equals(field)) {
            return EsFieldConstant.ES_FIELD_SUMMARY_EXACT;
        }
        if (EsFieldConstant.ES_FIELD_CONTENT.equals(field)) {
            return EsFieldConstant.ES_FIELD_CONTENT_EXACT;
        }
        return field;
    }

    private float fieldBoost(String field) {
        if (EsFieldConstant.ES_FIELD_TITLE.equals(field)) {
            return 8.0f;
        }
        if (EsFieldConstant.ES_FIELD_SHORT_TITLE.equals(field)) {
            return 5.0f;
        }
        if (EsFieldConstant.ES_FIELD_SUMMARY.equals(field)) {
            return 2.0f;
        }
        return 1.0f;
    }

    private Map<String, Object> matchPhrase(String field, String value) {
        return Collections.singletonMap("match_phrase", Collections.singletonMap(field, value));
    }

    private Map<String, Object> matchPhrase(String field, String value, float boost) {
        Map<String, Object> phrase = new LinkedHashMap<>();
        phrase.put("query", value);
        phrase.put("slop", 0);
        if (boost > 1.0f) {
            phrase.put("boost", boost);
        }
        return Collections.singletonMap("match_phrase", Collections.singletonMap(field, phrase));
    }

    private Map<String, Object> matchPhrasePrefix(String field, String value, float boost) {
        Map<String, Object> prefix = new LinkedHashMap<>();
        prefix.put("query", value);
        prefix.put("max_expansions", 50);
        prefix.put("boost", boost);
        return Collections.singletonMap("match_phrase_prefix", Collections.singletonMap(field, prefix));
    }

    private Map<String, Object> fuzzyMatch(String field, String value, float boost) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("query", value);
        match.put("operator", "and");
        match.put("fuzziness", "AUTO");
        match.put("prefix_length", 1);
        match.put("max_expansions", 50);
        match.put("boost", boost);
        return Collections.singletonMap("match", Collections.singletonMap(field, match));
    }

    private Map<String, Object> pinyinMatch(String field, String value, float boost) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("query", value);
        match.put("operator", "and");
        match.put("boost", boost);
        return Collections.singletonMap("match", Collections.singletonMap(field, match));
    }

    private Map<String, Object> wildcardContains(String field, String value, float boost) {
        Map<String, Object> wildcard = new LinkedHashMap<>();
        wildcard.put("value", "*" + escapeWildcard(value) + "*");
        wildcard.put("case_insensitive", true);
        if (boost > 1.0f) {
            wildcard.put("boost", boost);
        }
        return Collections.singletonMap("wildcard", Collections.singletonMap(field, wildcard));
    }

    private String escapeWildcard(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private Map<String, Object> term(String field, Object value) {
        return Collections.singletonMap("term", Collections.singletonMap(field, value));
    }

    private void addTermFilter(List<Map<String, Object>> filters, String field, Object value) {
        if (value != null) {
            filters.add(term(field, value));
        }
    }

    private void addEffectiveTermFilter(List<Map<String, Object>> filters, String field, Number value) {
        if (value != null && value.longValue() != -1L) {
            filters.add(term(field, value));
        }
    }

    private boolean hasAdminStructuredFilters(SearchArticleParams params) {
        return params.getArticleId() != null
                || params.getUserId() != null
                || StringUtils.isNotBlank(params.getUserName())
                || StringUtils.isNotBlank(params.getTitle())
                || StringUtils.isNotBlank(params.getUrlSlug())
                || (params.getOfficalStat() != null && params.getOfficalStat() != -1)
                || (params.getToppingStat() != null && params.getToppingStat() != -1)
                || (params.getColumnId() != null && params.getColumnId() != -1);
    }

    private int normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return PageParam.DEFAULT_PAGE_SIZE.intValue();
        }
        return (int) Math.min(pageSize, 100);
    }

    private long normalizedPageNumber(PageParam pageParam, int pageSize) {
        if (pageParam == null) {
            return 1L;
        }
        return pageParam.getPageNum() > 0 ? pageParam.getPageNum()
                : Math.max(1L, pageParam.getOffset() / Math.max(1, pageSize) + 1L);
    }

    private int keywordBootstrapSize(long offset, int pageSize) {
        long targetSize = Math.max(pageSize, offset + pageSize);
        return (int) Math.min(targetSize, KEYWORD_BOOTSTRAP_MAX_SIZE);
    }

    private ArticleSearchResult emptyResult() {
        ArticleSearchResult result = new ArticleSearchResult();
        result.setArticleIds(Collections.emptyList());
        result.setHighlights(Collections.emptyMap());
        result.setSnippets(Collections.emptyMap());
        result.setTotal(0L);
        return result;
    }

    private RequestOptions searchRequestOptions() {
        int timeout = Math.max(100, searchTimeoutMs);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(Math.max(50, searchConnectionRequestTimeoutMs))
                .build();
        return RequestOptions.DEFAULT.toBuilder().setRequestConfig(requestConfig).build();
    }

    private boolean allowSearchRequest() {
        long openUntil = circuitOpenUntilMillis.get();
        if (openUntil <= 0) {
            return true;
        }
        if (System.currentTimeMillis() < openUntil) {
            return false;
        }
        return halfOpenProbeInFlight.compareAndSet(false, true);
    }

    private void onSearchSuccess() {
        consecutiveSearchFailures.set(0);
        circuitOpenUntilMillis.set(0L);
        halfOpenProbeInFlight.set(false);
    }

    private void onSearchFailure(Exception error) {
        lastFailureTime.set(new Date());
        lastFailureMessage.set(StringUtils.abbreviate(StringUtils.defaultString(error.getMessage(),
                error.getClass().getSimpleName()), 300));
        int failures = consecutiveSearchFailures.incrementAndGet();
        if (halfOpenProbeInFlight.get() || failures >= Math.max(1, circuitFailureThreshold)) {
            long openUntil = System.currentTimeMillis() + Math.max(1L, circuitOpenSeconds) * 1000L;
            circuitOpenUntilMillis.set(openUntil);
            halfOpenProbeInFlight.set(false);
            log.warn("article search circuit opened: failures={}, openUntil={}", failures, new Date(openUntil));
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("paicoding.search.elasticsearch.circuit_open_total").increment();
            }
        }
    }

    private void releaseHalfOpenProbe() {
        halfOpenProbeInFlight.set(false);
    }

    private boolean rebuildInProgress() {
        String taskId = activeRebuildTaskId.get();
        ArticleSearchRebuildTask task = taskId == null ? null : rebuildTasks.get(taskId);
        return task != null && (ArticleSearchRebuildTask.PENDING.equals(task.getStatus())
                || ArticleSearchRebuildTask.RUNNING.equals(task.getStatus()));
    }

    private void recordEsQuery(String keyword, long elapsedNanos, String outcome) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.timer("paicoding.search.elasticsearch.latency", "outcome", outcome)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            registry.counter("paicoding.search.elasticsearch.requests_total", "outcome", outcome).increment();
        }
        if (elapsedMillis >= Math.max(1L, slowQueryThresholdMs)) {
            log.warn("slow Elasticsearch article query: elapsedMs={}, queryHash={}, outcome={}",
                    elapsedMillis, normalizeQuery(keyword).hashCode(), outcome);
            if (registry != null) {
                registry.counter("paicoding.search.elasticsearch.slow_total", "outcome", outcome).increment();
            }
        }
    }

    private void recordFallback(String reason) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("paicoding.search.fallback_total", "reason", reason).increment();
        }
    }

    private String safeHighlightFragment(String fragment) {
        if (fragment == null) {
            return null;
        }
        return escapeHtml(fragment).replace(HIGHLIGHT_PRE, "<em class=\"search-highlight\">")
                .replace(HIGHLIGHT_POST, "</em>");
    }

    private String emphasizeEscapedKeyword(String fragment, String keyword) {
        String escaped = escapeHtml(fragment);
        String escapedKeyword = escapeHtml(StringUtils.trimToEmpty(keyword));
        return StringUtils.isBlank(escapedKeyword) ? escaped
                : escaped.replace(escapedKeyword, "<em class=\"search-highlight\">" + escapedKeyword + "</em>");
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void recordQueryOutcome(String keyword, ArticleSearchResult result) {
        String normalized = normalizeQuery(keyword);
        if (StringUtils.isBlank(normalized) || result == null) {
            return;
        }
        try {
            RedisClient.hIncr(QUERY_COUNT_KEY, normalized, 1);
            RedisClient.zIncrBy(result.getTotal() > 0 ? HOT_QUERY_KEY : ZERO_RESULT_QUERY_KEY, normalized, 1);
        } catch (Exception e) {
            log.debug("failed to record article search metrics", e);
        }
    }

    private String normalizeQuery(String keyword) {
        return StringUtils.normalizeSpace(StringUtils.trimToEmpty(keyword)).toLowerCase(Locale.ROOT);
    }

    private String searchResultCacheKey(String keyword, Map<String, Object> body) {
        return "article_search:result:" + indexGeneration() + ":" + searchSignature(keyword, body);
    }

    private ArticleSearchResult readCachedResult(String key) {
        try {
            String value = RedisClient.getStr(key);
            return StringUtils.isBlank(value) ? null : objectMapper.readValue(value, ArticleSearchResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCachedResult(String key, ArticleSearchResult result) {
        try {
            RedisClient.setStrWithExpire(key, objectMapper.writeValueAsString(result), searchCacheTtlSeconds);
        } catch (Exception e) {
            log.debug("failed to cache article search result", e);
        }
    }

    private String cursorKey(String keyword, Map<String, Object> body, long pageNum) {
        return "article_search:cursor:" + indexGeneration() + ":" + searchSignature(keyword, body) + ":" + pageNum;
    }

    private String searchSignature(String keyword, Map<String, Object> body) {
        return normalizeQuery(keyword).hashCode() + ":" + Objects.hash(body.get("size"), body.get("query"));
    }

    private List<Object> readCursor(String keyword, Map<String, Object> body, long pageNum) {
        try {
            String value = RedisClient.getStr(cursorKey(keyword, body, pageNum));
            return StringUtils.isBlank(value) ? Collections.emptyList()
                    : objectMapper.readValue(value, new TypeReference<List<Object>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void writeCursor(String keyword, Map<String, Object> body, long pageNum, List<Object> cursor) {
        try {
            RedisClient.setStrWithExpire(cursorKey(keyword, body, pageNum), objectMapper.writeValueAsString(cursor), searchCursorTtlSeconds);
        } catch (Exception e) {
            log.debug("failed to cache article search cursor", e);
        }
    }

    private void indexOne(ArticleSearchDocumentDTO document) throws IOException {
        indexOne(document, indexName());
    }

    /** Index API 本身即为 upsert，并以数据库更新时间做外部版本防止重建批次覆盖新事件。 */
    private void indexOne(ArticleSearchDocumentDTO document, String targetIndex) throws IOException {
        Request request = new Request("PUT", "/" + targetIndex + "/_doc/" + document.getArticleId());
        request.addParameter("version_type", "external_gte");
        request.addParameter("version", String.valueOf(documentVersion(document)));
        request.setJsonEntity(objectMapper.writeValueAsString(toIndexDocument(document)));
        restClient().performRequest(request);
    }

    private void bulkIndex(List<ArticleSearchDocumentDTO> documents) throws IOException {
        bulkIndex(documents, indexName());
    }

    private void bulkIndex(List<ArticleSearchDocumentDTO> documents, String targetIndex) throws IOException {
        StringBuilder body = new StringBuilder();
        for (ArticleSearchDocumentDTO document : documents) {
            body.append("{\"index\":{\"_index\":\"").append(targetIndex).append("\",\"_id\":\"")
                    .append(document.getArticleId()).append("\",\"version_type\":\"external_gte\",\"version\":")
                    .append(documentVersion(document)).append("}}\n");
            body.append(objectMapper.writeValueAsString(toIndexDocument(document))).append("\n");
        }
        Request request = new Request("POST", "/_bulk");
        request.setEntity(new StringEntity(body.toString(), ContentType.create("application/x-ndjson", Consts.UTF_8)));
        Response response = restClient().performRequest(request);
        Map<String, Object> responseBody = objectMapper.readValue(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), MAP_TYPE);
        if (Boolean.TRUE.equals(responseBody.get("errors"))) {
            throw new IOException("bulk index article search documents failed: " + firstBulkError(responseBody));
        }
    }

    private void executeFullRebuild(ArticleSearchRebuildTask task) {
        task.setStatus(ArticleSearchRebuildTask.RUNNING);
        task.setStartTime(new Date());
        task.setMessage("creating versioned index");
        try {
            task.setTotal(defaultLong(articleDao.countSearchableArticles()));
            createIndex(task.getTargetIndex());
            task.setMessage("scanning MySQL by article ID cursor");
            long lastId = 0L;
            int batchSize = Math.max(1, rebuildBatchSize);
            while (!abortRequested(task)) {
                List<ArticleSearchDocumentDTO> documents = articleDao.listArticleSearchDocumentsAfterId(lastId, batchSize);
                if (documents == null || documents.isEmpty()) {
                    break;
                }
                long startExclusive = lastId;
                lastId = documents.get(documents.size() - 1).getArticleId();
                if (bulkIndexWithRetry(documents, task.getTargetIndex(), rebuildMaxRetries)) {
                    task.setSucceeded(task.getSucceeded() + documents.size());
                } else {
                    recordFailedBatch(task, startExclusive, lastId, documents.size(), rebuildMaxRetries,
                            "bulk request failed after retries");
                }
                task.setProcessed(task.getProcessed() + documents.size());
                updateProgress(task);
            }
            if (abortRequested(task)) {
                finishTask(task, ArticleSearchRebuildTask.ABORTED, "rebuild aborted; alias was not changed");
                return;
            }
            if (!task.getFailedBatches().isEmpty()) {
                finishTask(task, ArticleSearchRebuildTask.FAILED, "rebuild finished with failed batches; alias was not changed");
                return;
            }
            activateRebuiltIndex(task);
        } catch (Exception e) {
            log.error("failed to rebuild article search index: target={}", task.getTargetIndex(), e);
            finishTask(task, ArticleSearchRebuildTask.FAILED, e.getMessage());
        } finally {
            activeRebuildTaskId.compareAndSet(task.getTaskId(), null);
        }
    }

    private void executeFailedBatchRetry(ArticleSearchRebuildTask task) {
        task.setStatus(ArticleSearchRebuildTask.RUNNING);
        task.setMessage("retrying failed batches");
        List<ArticleSearchRebuildTask.FailedBatch> failed = new ArrayList<>(task.getFailedBatches());
        task.getFailedBatches().clear();
        task.setFailed(0);
        try {
            for (ArticleSearchRebuildTask.FailedBatch batch : failed) {
                if (abortRequested(task)) {
                    task.getFailedBatches().add(batch);
                    continue;
                }
                List<ArticleSearchDocumentDTO> documents = articleDao.listArticleSearchDocumentsByIdRange(
                        batch.getStartExclusive(), batch.getEndInclusive());
                if (bulkIndexWithRetry(documents, task.getTargetIndex(), rebuildMaxRetries)) {
                    task.setSucceeded(task.getSucceeded() + documents.size());
                } else {
                    recordFailedBatch(task, batch.getStartExclusive(), batch.getEndInclusive(), documents.size(),
                            batch.getAttempts() + rebuildMaxRetries, "retry failed");
                }
            }
            task.setFailed(task.getFailedBatches().stream()
                    .mapToLong(ArticleSearchRebuildTask.FailedBatch::getSize).sum());
            if (abortRequested(task)) {
                finishTask(task, ArticleSearchRebuildTask.ABORTED, "retry aborted; alias was not changed");
            } else if (!task.getFailedBatches().isEmpty()) {
                finishTask(task, ArticleSearchRebuildTask.FAILED, "some failed batches still could not be indexed");
            } else {
                activateRebuiltIndex(task);
            }
        } catch (Exception e) {
            log.error("failed to retry article index batches: taskId={}", task.getTaskId(), e);
            finishTask(task, ArticleSearchRebuildTask.FAILED, e.getMessage());
        } finally {
            activeRebuildTaskId.compareAndSet(task.getTaskId(), null);
        }
    }

    private boolean bulkIndexWithRetry(List<ArticleSearchDocumentDTO> documents, String targetIndex, int maxRetries) {
        int attempts = Math.max(1, maxRetries);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (documents != null && !documents.isEmpty()) {
                    bulkIndex(documents, targetIndex);
                }
                return true;
            } catch (Exception e) {
                log.warn("article bulk index failed: target={}, attempt={}/{}", targetIndex, attempt, attempts, e);
            }
        }
        return false;
    }

    private void activateRebuiltIndex(ArticleSearchRebuildTask task) throws IOException {
        refreshIndex(task.getTargetIndex());
        switchAlias(task.getTargetIndex());
        indexChecked.set(true);
        markIndexCoverage(true);
        keywordBootstrapInFlight.clear();
        resetIndexGeneration();
        task.setProgress(100);
        finishTask(task, ArticleSearchRebuildTask.SUCCEEDED, "versioned index activated through alias");
        log.info("rebuilt article search index: alias={}, index={}, documents={}",
                indexName(), task.getTargetIndex(), task.getSucceeded());
    }

    private void recordFailedBatch(ArticleSearchRebuildTask task, long startExclusive, long endInclusive,
                                   int size, int attempts, String error) {
        ArticleSearchRebuildTask.FailedBatch batch = new ArticleSearchRebuildTask.FailedBatch();
        batch.setStartExclusive(startExclusive);
        batch.setEndInclusive(endInclusive);
        batch.setSize(size);
        batch.setAttempts(attempts);
        batch.setError(error);
        task.getFailedBatches().add(batch);
        task.setFailed(task.getFailed() + size);
    }

    private void updateProgress(ArticleSearchRebuildTask task) {
        task.setProgress(task.getTotal() <= 0 ? 100 : (int) Math.min(99, task.getProcessed() * 100 / task.getTotal()));
        task.setMessage("indexed " + task.getProcessed() + " of " + task.getTotal());
    }

    private boolean abortRequested(ArticleSearchRebuildTask task) {
        AtomicBoolean abort = rebuildAbortFlags.get(task.getTaskId());
        return abort != null && abort.get();
    }

    private void finishTask(ArticleSearchRebuildTask task, String status, String message) {
        task.setStatus(status);
        task.setMessage(StringUtils.defaultString(message));
        task.setFinishTime(new Date());
    }

    private ArticleSearchRebuildTask requireTask(String taskId) {
        ArticleSearchRebuildTask task = rebuildTasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("article search rebuild task not found: " + taskId);
        }
        return task;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private long documentVersion(ArticleSearchDocumentDTO document) {
        return document == null || document.getUpdateTime() == null ? 1L : Math.max(1L, document.getUpdateTime().getTime());
    }

    private void deleteDocument(String index, Long articleId, long version) throws IOException {
        Request request = new Request("DELETE", "/" + index + "/_doc/" + articleId);
        request.addParameter("version_type", "external_gte");
        request.addParameter("version", String.valueOf(version));
        try {
            restClient().performRequest(request);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                throw e;
            }
        }
    }

    private String activeRebuildTarget() {
        String taskId = activeRebuildTaskId.get();
        ArticleSearchRebuildTask task = taskId == null ? null : rebuildTasks.get(taskId);
        if (task == null || !ArticleSearchRebuildTask.RUNNING.equals(task.getStatus())) {
            return null;
        }
        try {
            return indexExists(task.getTargetIndex()) ? task.getTargetIndex() : null;
        } catch (IOException e) {
            log.warn("failed to inspect active article rebuild target: {}", task.getTargetIndex(), e);
            return null;
        }
    }

    private Map<String, Object> toIndexDocument(ArticleSearchDocumentDTO document) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("articleId", document.getArticleId());
        body.put("authorId", document.getAuthorId());
        body.put("authorName", document.getAuthorName());
        body.put("title", document.getTitle());
        body.put(EsFieldConstant.ES_FIELD_TITLE_EXACT, document.getTitle());
        body.put("shortTitle", document.getShortTitle());
        body.put(EsFieldConstant.ES_FIELD_SHORT_TITLE_EXACT, document.getShortTitle());
        body.put("urlSlug", document.getUrlSlug());
        body.put("summary", document.getSummary());
        body.put(EsFieldConstant.ES_FIELD_SUMMARY_EXACT, document.getSummary());
        body.put("content", document.getContent());
        body.put(EsFieldConstant.ES_FIELD_CONTENT_EXACT, document.getContent());
        body.put("status", document.getStatus());
        body.put("officalStat", document.getOfficalStat());
        body.put("toppingStat", document.getToppingStat());
        body.put("creamStat", document.getCreamStat());
        body.put("qualityScore", qualityScore(document));
        body.put("readCount", defaultLong(document.getReadCount()));
        body.put("praiseCount", defaultLong(document.getPraiseCount()));
        body.put("deleted", document.getDeleted());
        body.put("columnIds", document.parseColumnIds());
        body.put("createTime", document.getCreateTime());
        body.put("updateTime", document.getUpdateTime());
        return body;
    }

    private long qualityScore(ArticleSearchDocumentDTO document) {
        return flag(document.getCreamStat()) * 5L + flag(document.getOfficalStat()) * 3L
                + flag(document.getToppingStat()) * 2L + 1L;
    }

    private int flag(Integer value) {
        return value != null && value > 0 ? 1 : 0;
    }

    private boolean ensureIndex() {
        if (indexChecked.get()) {
            return true;
        }
        synchronized (indexChecked) {
            if (indexChecked.get()) {
                return true;
            }
            try {
                if (!indexExists(indexName())) {
                    if (indexExists(legacyIndexName())) {
                        addAlias(legacyIndexName());
                    } else {
                        String initialIndex = versionedIndexName();
                        createIndex(initialIndex);
                        addAlias(initialIndex);
                    }
                    markIndexCoverage(false);
                    resetIndexGeneration();
                } else if (ensureExactFieldsMapping()) {
                    markIndexCoverage(false);
                    resetIndexGeneration();
                    keywordBootstrapInFlight.clear();
                }
                indexChecked.set(true);
                return true;
            } catch (Exception e) {
                log.error("failed to ensure article search index: {}", indexName(), e);
                return false;
            }
        }
    }

    private void markIndexCoverage(boolean fullIndex) {
        fullIndexAvailable = fullIndex;
        indexCoverageChecked.set(true);
    }

    private boolean indexExists(String name) throws IOException {
        try {
            Request request = new Request("HEAD", "/" + name);
            request.setOptions(searchRequestOptions());
            Response response = restClient().performRequest(request);
            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode >= HttpStatus.SC_OK && statusCode < HttpStatus.SC_MULTIPLE_CHOICES;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    private boolean ensureExactFieldsMapping() throws IOException {
        if (hasExactFieldsMapping()) {
            return false;
        }
        Request request = new Request("PUT", "/" + indexName() + "/_mapping");
        request.setOptions(searchRequestOptions());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("properties", exactFieldsMapping());
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        restClient().performRequest(request);
        log.info("added exact article search fields to index mapping: index={}", indexName());
        return true;
    }

    private boolean hasExactFieldsMapping() throws IOException {
        Request request = new Request("GET", "/" + indexName() + "/_mapping");
        request.setOptions(searchRequestOptions());
        Response response = restClient().performRequest(request);
        Map<String, Object> root = objectMapper.readValue(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), MAP_TYPE);
        Map<String, Object> index = castMap(root.get(indexName()));
        if (index.isEmpty() && !root.isEmpty()) {
            index = castMap(root.values().iterator().next());
        }
        Map<String, Object> mappings = castMap(index.get("mappings"));
        Map<String, Object> properties = castMap(mappings.get("properties"));
        return properties.containsKey(EsFieldConstant.ES_FIELD_TITLE_EXACT)
                && properties.containsKey(EsFieldConstant.ES_FIELD_SHORT_TITLE_EXACT)
                && properties.containsKey(EsFieldConstant.ES_FIELD_SUMMARY_EXACT)
                && properties.containsKey(EsFieldConstant.ES_FIELD_CONTENT_EXACT)
                && properties.containsKey("qualityScore")
                && properties.containsKey("readCount")
                && properties.containsKey("praiseCount")
                && properties.containsKey("createTime");
    }

    private boolean bootstrapKeywordDocumentsOnce(String keyword, int size, boolean includeBody, boolean onlineOnly) throws IOException {
        String trimmedKeyword = StringUtils.trimToEmpty(keyword);
        if (StringUtils.isBlank(trimmedKeyword)) {
            return false;
        }

        int limit = normalizeBootstrapSize(size);
        String doneKey = keywordBootstrapDoneKey(trimmedKeyword, limit, includeBody, onlineOnly);
        if (StringUtils.isNotBlank(RedisClient.getStr(doneKey))) {
            return false;
        }
        if (!keywordBootstrapInFlight.add(doneKey)) {
            return false;
        }
        String lockKey = keywordBootstrapLockKey(doneKey);
        if (!Boolean.TRUE.equals(RedisClient.setStrIfAbsentWithExpire(lockKey, "1", KEYWORD_BOOTSTRAP_LOCK_TTL_SECONDS))) {
            keywordBootstrapInFlight.remove(doneKey);
            return false;
        }

        try {
            List<ArticleSearchDocumentDTO> documents = articleDao.listArticleSearchDocumentsByKeyword(trimmedKeyword, includeBody, onlineOnly, limit);
            if (documents == null || documents.isEmpty()) {
                RedisClient.setStrWithExpire(doneKey, "0", KEYWORD_BOOTSTRAP_DONE_TTL_SECONDS);
                recordQueryOutcome(trimmedKeyword, emptyResult());
                log.info("no article search documents found for keyword bootstrap: index={}, keyword={}", indexName(), trimmedKeyword);
                return false;
            }

            bulkIndex(documents);
            refreshIndex();
            RedisClient.setStrWithExpire(doneKey, String.valueOf(documents.size()), KEYWORD_BOOTSTRAP_DONE_TTL_SECONDS);
            log.info("bootstrapped article search index by keyword: index={}, keyword={}, indexed={}",
                    indexName(), trimmedKeyword, documents.size());
            return true;
        } finally {
            keywordBootstrapInFlight.remove(doneKey);
            RedisClient.del(lockKey);
        }
    }

    private String keywordBootstrapKey(String keyword, int size, boolean includeBody, boolean onlineOnly) {
        return (onlineOnly ? "online:" : "all:") + (includeBody ? "body:" : "title:") + size + ":" + keyword.toLowerCase(Locale.ROOT);
    }

    private String keywordBootstrapDoneKey(String keyword, int size, boolean includeBody, boolean onlineOnly) {
        return "article_search:keyword_bootstrap:done:" + indexName() + ":" + indexGeneration() + ":"
                + keywordBootstrapKey(keyword, size, includeBody, onlineOnly);
    }

    private String keywordBootstrapLockKey(String doneKey) {
        return doneKey.replace(":done:", ":lock:");
    }

    private String indexGenerationKey() {
        return "article_search:index_generation:" + indexName();
    }

    private String indexGeneration() {
        String key = indexGenerationKey();
        String generation = RedisClient.getStr(key);
        if (StringUtils.isNotBlank(generation)) {
            return generation;
        }
        generation = String.valueOf(System.currentTimeMillis());
        RedisClient.setStr(key, generation);
        return generation;
    }

    private void resetIndexGeneration() {
        RedisClient.setStr(indexGenerationKey(), String.valueOf(System.currentTimeMillis()));
    }

    private int normalizeBootstrapSize(int size) {
        return Math.min(Math.max(size, 1), KEYWORD_BOOTSTRAP_MAX_SIZE);
    }

    private boolean isFullIndexAvailable() throws IOException {
        if (indexCoverageChecked.get()) {
            return fullIndexAvailable;
        }
        synchronized (indexCoverageChecked) {
            if (indexCoverageChecked.get()) {
                return fullIndexAvailable;
            }
            long indexedCount = countIndexDocuments();
            Long dbCount = articleDao.countSearchableArticles();
            fullIndexAvailable = dbCount != null && dbCount > 0 && indexedCount >= dbCount;
            indexCoverageChecked.set(true);
            return fullIndexAvailable;
        }
    }

    private long countIndexDocuments() throws IOException {
        Request request = new Request("GET", "/" + indexName() + "/_count");
        request.setOptions(searchRequestOptions());
        Response response = restClient().performRequest(request);
        Map<String, Object> body = objectMapper.readValue(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), MAP_TYPE);
        Long count = asLong(body.get("count"));
        return count == null ? 0L : count;
    }

    private void refreshIndex() throws IOException {
        refreshIndex(indexName());
    }

    private void refreshIndex(String index) throws IOException {
        restClient().performRequest(new Request("POST", "/" + index + "/_refresh"));
    }

    private void createIndex(String index) throws IOException {
        Request request = new Request("PUT", "/" + index);
        request.setJsonEntity(objectMapper.writeValueAsString(indexDefinition()));
        restClient().performRequest(request);
    }

    private void addAlias(String index) throws IOException {
        Request request = new Request("POST", "/_aliases");
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("index", index);
        add.put("alias", indexName());
        request.setJsonEntity(objectMapper.writeValueAsString(
                Collections.singletonMap("actions", Collections.singletonList(Collections.singletonMap("add", add)))));
        restClient().performRequest(request);
    }

    private void switchAlias(String targetIndex) throws IOException {
        Request request = new Request("POST", "/_aliases");
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> remove = new LinkedHashMap<>();
        remove.put("index", "*");
        remove.put("alias", indexName());
        remove.put("must_exist", false);
        actions.add(Collections.singletonMap("remove", remove));
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("index", targetIndex);
        add.put("alias", indexName());
        add.put("is_write_index", true);
        actions.add(Collections.singletonMap("add", add));
        request.setJsonEntity(objectMapper.writeValueAsString(Collections.singletonMap("actions", actions)));
        restClient().performRequest(request);
    }

    private String versionedIndexName() {
        return indexName() + "_v" + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
    }

    private Map<String, Object> indexDefinition() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        settings.put("analysis", analysisSettings());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("articleId", type("long"));
        properties.put("authorId", type("long"));
        properties.put("authorName", textField());
        properties.put(EsFieldConstant.ES_FIELD_TITLE, textField());
        properties.put(EsFieldConstant.ES_FIELD_SHORT_TITLE, textField());
        properties.put("urlSlug", keywordField());
        properties.put(EsFieldConstant.ES_FIELD_SUMMARY, textField());
        properties.put(EsFieldConstant.ES_FIELD_CONTENT, textField());
        properties.putAll(exactFieldsMapping());
        properties.put("status", type("integer"));
        properties.put("deleted", type("integer"));
        properties.put("officalStat", type("integer"));
        properties.put("toppingStat", type("integer"));
        properties.put("creamStat", type("integer"));
        properties.put("qualityScore", type("long"));
        properties.put("readCount", type("long"));
        properties.put("praiseCount", type("long"));
        properties.put("columnIds", type("long"));
        properties.put("createTime", dateField());
        properties.put("updateTime", dateField());

        Map<String, Object> mappings = new LinkedHashMap<>();
        mappings.put("properties", properties);
        body.put("mappings", mappings);
        return body;
    }

    private Map<String, Object> exactFieldsMapping() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(EsFieldConstant.ES_FIELD_TITLE_EXACT, wildcardField());
        properties.put(EsFieldConstant.ES_FIELD_SHORT_TITLE_EXACT, wildcardField());
        properties.put(EsFieldConstant.ES_FIELD_SUMMARY_EXACT, wildcardField());
        properties.put(EsFieldConstant.ES_FIELD_CONTENT_EXACT, wildcardField());
        properties.put("qualityScore", type("long"));
        properties.put("readCount", type("long"));
        properties.put("praiseCount", type("long"));
        properties.put("createTime", dateField());
        return properties;
    }

    private Map<String, Object> textField() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "text");
        field.put("analyzer", "pai_zh_index");
        field.put("search_analyzer", "pai_zh_search");
        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("type", "keyword");
        keyword.put("ignore_above", 256);
        Map<String, Object> pinyin = new LinkedHashMap<>();
        pinyin.put("type", "text");
        pinyin.put("analyzer", "pai_pinyin_index");
        pinyin.put("search_analyzer", "pai_pinyin_search");
        Map<String, Object> subFields = new LinkedHashMap<>();
        subFields.put("keyword", keyword);
        subFields.put("pinyin", pinyin);
        field.put("fields", subFields);
        return field;
    }

    private Map<String, Object> analysisSettings() {
        Map<String, Object> filters = new LinkedHashMap<>();
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("type", "stop");
        stop.put("stopwords", java.util.Arrays.asList("的", "了", "和", "是", "在", "与", "及", "或", "一个", "如何"));
        filters.put("pai_stop", stop);
        Map<String, Object> synonyms = new LinkedHashMap<>();
        synonyms.put("type", "synonym_graph");
        synonyms.put("lenient", true);
        synonyms.put("synonyms", java.util.Arrays.asList(
                "java虚拟机,jvm", "数据库,db", "缓存,cache", "消息队列,mq", "人工智能,ai",
                "搜索引擎,elasticsearch,es", "微服务,microservice", "前端,frontend", "后端,backend"));
        filters.put("pai_synonym", synonyms);
        Map<String, Object> pinyin = new LinkedHashMap<>();
        pinyin.put("type", "pinyin");
        pinyin.put("keep_full_pinyin", true);
        pinyin.put("keep_joined_full_pinyin", true);
        pinyin.put("keep_first_letter", true);
        pinyin.put("keep_original", true);
        pinyin.put("limit_first_letter_length", 32);
        pinyin.put("remove_duplicated_term", true);
        filters.put("pai_pinyin", pinyin);

        Map<String, Object> analyzers = new LinkedHashMap<>();
        analyzers.put("pai_zh_index", analyzer("ik_max_word", "lowercase", "pai_stop"));
        analyzers.put("pai_zh_search", analyzer("ik_smart", "lowercase", "pai_stop", "pai_synonym"));
        analyzers.put("pai_pinyin_index", analyzer("ik_smart", "lowercase", "pai_pinyin"));
        analyzers.put("pai_pinyin_search", analyzer("keyword", "lowercase", "pai_pinyin"));
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("filter", filters);
        analysis.put("analyzer", analyzers);
        return analysis;
    }

    private Map<String, Object> analyzer(String tokenizer, String... filters) {
        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put("type", "custom");
        analyzer.put("tokenizer", tokenizer);
        analyzer.put("filter", java.util.Arrays.asList(filters));
        return analyzer;
    }

    private Map<String, Object> dateField() {
        Map<String, Object> field = type("date");
        field.put("format", "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis");
        return field;
    }

    private Map<String, Object> keywordField() {
        Map<String, Object> field = type("keyword");
        field.put("ignore_above", 256);
        return field;
    }

    private Map<String, Object> wildcardField() {
        return type("wildcard");
    }

    private Map<String, Object> type(String type) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        return field;
    }

    private String firstBulkError(Map<String, Object> responseBody) {
        List<Map<String, Object>> items = castList(responseBody.get("items"));
        for (Map<String, Object> item : items) {
            Map<String, Object> index = castMap(item.get("index"));
            Map<String, Object> error = castMap(index.get("error"));
            if (!error.isEmpty()) {
                return error.toString();
            }
        }
        return "unknown bulk error";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.emptyList();
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && StringUtils.isNumeric((String) value)) {
            return Long.valueOf((String) value);
        }
        return null;
    }

    private String indexName() {
        return StringUtils.defaultIfBlank(articleAlias, "paicoding_article");
    }

    private String legacyIndexName() {
        return StringUtils.defaultIfBlank(articleIndex, EsIndexConstant.ES_INDEX_ARTICLE);
    }

    private RestClient restClient() {
        RestHighLevelClient client = restHighLevelClientProvider.getIfAvailable();
        return client == null ? null : client.getLowLevelClient();
    }
}

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.enums.PushStatusEnum;
import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchDomainEventConsumer;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchResult;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.service.impl.DomainEventConsumeService;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.Request;
import org.springframework.data.redis.core.RedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated loopback ES HTTP responses and mocked DAO/JDBC; never contacts a database or ES cluster. */
public class SearchFailureRegressionTest {
    private ArticleSearchResult parse(String json) throws Throwable {
        ArticleSearchServiceImpl service = new ArticleSearchServiceImpl();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        Method method = ArticleSearchServiceImpl.class.getDeclaredMethod("parseSearchResponse", String.class, String.class);
        method.setAccessible(true);
        try {
            return (ArticleSearchResult) method.invoke(service, "bench", json);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private String response(boolean timedOut, int failed) {
        return "{\"timed_out\":" + timedOut + ",\"_shards\":{\"total\":2,\"successful\":" + (2 - failed)
                + ",\"failed\":" + failed + "},\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_id\":\"42\"}]}}";
    }

    @Test void timedOutResponseRejectsPartialHits() {
        assertThrows(IOException.class, () -> parse(response(true, 0)));
    }

    @Test void failedShardResponseRejectsPartialHits() {
        assertThrows(IOException.class, () -> parse(response(false, 1)));
    }

    @Test void healthyResponseStillReturnsHits() throws Throwable {
        assertEquals(Collections.singletonList(42L), parse(response(false, 0)).getArticleIds());
    }

    @Test void syncFailurePropagates() throws Exception {
        try (Fixture fixture = new Fixture(503, true)) {
            assertThrows(IllegalStateException.class, () -> fixture.service.syncArticle(42L));
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test void indexInitializationFailurePropagates() throws Exception {
        try (Fixture fixture = new Fixture(503, false)) {
            assertThrows(IllegalStateException.class, () -> fixture.service.syncArticle(42L));
            assertEquals(1, fixture.requests.get());
            verifyNoInteractions(fixture.dao);
        }
    }

    @Test void deleteFailurePropagates() throws Exception {
        try (Fixture fixture = new Fixture(503, true)) {
            assertThrows(IllegalStateException.class, () -> fixture.service.deleteArticle(42L));
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test void missingDocumentDeleteRemainsIdempotent() throws Exception {
        try (Fixture fixture = new Fixture(404, true)) {
            assertDoesNotThrow(() -> fixture.service.deleteArticle(42L));
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test void successfulSyncAndDeleteStillComplete() throws Exception {
        try (Fixture fixture = new Fixture(200, true)) {
            assertDoesNotThrow(() -> fixture.service.syncArticle(42L));
            assertDoesNotThrow(() -> fixture.service.deleteArticle(42L));
            assertEquals(2, fixture.requests.get());
        }
    }

    @Test void disabledAndNullInputsRemainNoOps() throws Exception {
        try (Fixture fixture = new Fixture(503, true)) {
            fixture.service.syncArticle(null);
            fixture.service.deleteArticle(null);
            ReflectionTestUtils.setField(fixture.service, "openES", false);
            fixture.service.syncArticle(42L);
            fixture.service.deleteArticle(42L);
            assertEquals(0, fixture.requests.get());
            verifyNoInteractions(fixture.dao);
        }
    }

    @Test void createEventFailureReachesConsumeBoundary() throws Exception {
        assertEventFailure("ARTICLE_CREATE");
    }

    @Test void deleteEventFailureReachesConsumeBoundary() throws Exception {
        assertEventFailure("ARTICLE_DELETE");
    }

    @Test void mockedConnectionRefusedUsesExistingFallback() throws Exception {
        // Deterministic exception injection, separate from the unchanged real closed-port probe.
        RestClient transport = mock(RestClient.class);
        when(transport.performRequest(any(Request.class))).thenThrow(new ConnectException("Connection refused (benchmark injected)"));
        RedisClient.register(mock(RedisTemplate.class));
        try (RestHighLevelClient client = new InjectedClient(transport)) {
            ArticleSearchServiceImpl service = new ArticleSearchServiceImpl();
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("es", client);
            factory.registerSingleton("metrics", new SimpleMeterRegistry());
            ReflectionTestUtils.setField(service, "restHighLevelClientProvider", factory.getBeanProvider(RestHighLevelClient.class));
            ReflectionTestUtils.setField(service, "meterRegistryProvider", factory.getBeanProvider(MeterRegistry.class));
            ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(service, "openES", true);
            ReflectionTestUtils.setField(service, "searchMaxPage", 50);
            ReflectionTestUtils.setField(service, "fullIndexAvailable", true);
            ReflectionTestUtils.setField(service, "circuitFailureThreshold", 5);
            ((AtomicBoolean) ReflectionTestUtils.getField(service, "indexChecked")).set(true);
            ((AtomicBoolean) ReflectionTestUtils.getField(service, "indexCoverageChecked")).set(true);
            assertNull(service.searchOnlineArticleIds("bench", PageParam.newPageInstance(1L, 10L)));
            assertTrue(service.runtimeStatus().getLastFailure().contains("Connection refused"));
            verify(transport).performRequest(any(Request.class));
        }
    }

    private static class InjectedClient extends RestHighLevelClient {
        InjectedClient(RestClient transport) { super(transport, client -> {}, Collections.emptyList()); }
    }

    private void assertEventFailure(String eventType) throws Exception {
        try (Fixture fixture = new Fixture(503, true)) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
            ArticleSearchDomainEventConsumer consumer = new ArticleSearchDomainEventConsumer(
                    fixture.service, new DomainEventConsumeService(jdbc));
            DomainEvent event = new DomainEvent();
            event.setEventId("bench-es-failure-" + eventType);
            event.setEventType(eventType);
            DomainEvent.Target target = new DomainEvent.Target();
            target.setId(42L);
            event.setTarget(target);
            assertThrows(IllegalStateException.class, () -> consumer.consume(event));
            assertEquals(1, fixture.requests.get());
            // A real transaction rollback requires the separately isolated outbox suite.
        }
    }

    private static class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        final RestHighLevelClient client;
        final ArticleDao dao = mock(ArticleDao.class);
        final ArticleSearchServiceImpl service = new ArticleSearchServiceImpl();

        Fixture(int status, boolean indexReady) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try {
                    byte[] bytes = "{\"result\":\"benchmark-stub\"}".getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
                    if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
            client = new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort(), "http")));
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("es", client);
            ReflectionTestUtils.setField(service, "restHighLevelClientProvider", factory.getBeanProvider(RestHighLevelClient.class));
            ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(service, "openES", true);
            ReflectionTestUtils.setField(service, "articleAlias", "bench_es_regression_only");
            ReflectionTestUtils.setField(service, "articleDao", dao);
            ((AtomicBoolean) ReflectionTestUtils.getField(service, "indexChecked")).set(indexReady);
            ArticleSearchDocumentDTO document = new ArticleSearchDocumentDTO();
            document.setArticleId(42L);
            document.setStatus(PushStatusEnum.ONLINE.getCode());
            document.setDeleted(YesOrNoEnum.NO.getCode());
            when(dao.queryArticleSearchDocument(42L)).thenReturn(document);
        }

        @Override public void close() throws Exception {
            try { client.close(); } finally { server.stop(0); }
        }
    }
}

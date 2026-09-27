package com.github.paicoding.forum.service.article.service.search.impl;

import com.github.paicoding.forum.service.constant.EsFieldConstant;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRuntimeStatus;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchState;
import org.elasticsearch.client.RequestOptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArticleSearchServiceImplTest {

    private final ArticleSearchServiceImpl service = new ArticleSearchServiceImpl();

    @Test
    void highlightEscapesSourceAndOnlyRestoresControlledEmTag() {
        String fragment = "<img src=x onerror=alert(1)>__PAI_HIGHLIGHT_START__Java__PAI_HIGHLIGHT_END__";

        String result = ReflectionTestUtils.invokeMethod(service, "safeHighlightFragment", fragment);

        assertTrue(result.contains("&lt;img src=x onerror=alert(1)&gt;"));
        assertTrue(result.contains("<em class=\"search-highlight\">Java</em>"));
        assertFalse(result.contains("<img"));
    }

    @Test
    void queryContainsAllRecallRoutesAndFunctionScore() {
        Map<String, Object> keywordQuery = ReflectionTestUtils.invokeMethod(service, "articleKeywordQuery", "jvaa",
                new String[]{EsFieldConstant.ES_FIELD_TITLE, EsFieldConstant.ES_FIELD_SUMMARY});
        Map<String, Object> definition = ReflectionTestUtils.invokeMethod(service, "indexDefinition");

        String query = String.valueOf(keywordQuery);
        String mapping = String.valueOf(definition);
        assertTrue(query.contains("match_phrase"));
        assertTrue(query.contains("match_phrase_prefix"));
        assertTrue(query.contains("fuzziness=AUTO"));
        assertTrue(query.contains("title.pinyin"));
        assertTrue(mapping.contains("pai_synonym"));
        assertTrue(mapping.contains("pai_stop"));
        assertTrue(mapping.contains("pai_pinyin"));
    }

    @Test
    void disabledElasticsearchHasExplicitUnavailableState() {
        ReflectionTestUtils.setField(service, "openES", false);

        ArticleSearchRuntimeStatus status = service.runtimeStatus();

        assertEquals(ArticleSearchState.ES_UNAVAILABLE, status.getState());
        assertTrue(status.getMessage().contains("MySQL title fallback"));
    }

    @Test
    void webSearchUsesShortRequestTimeouts() {
        ReflectionTestUtils.setField(service, "searchTimeoutMs", 1200);
        ReflectionTestUtils.setField(service, "searchConnectionRequestTimeoutMs", 150);

        RequestOptions options = ReflectionTestUtils.invokeMethod(service, "searchRequestOptions");

        assertEquals(1200, options.getRequestConfig().getConnectTimeout());
        assertEquals(1200, options.getRequestConfig().getSocketTimeout());
        assertEquals(150, options.getRequestConfig().getConnectionRequestTimeout());
    }
}

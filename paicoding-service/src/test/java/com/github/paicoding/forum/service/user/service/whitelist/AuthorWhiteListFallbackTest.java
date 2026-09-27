package com.github.paicoding.forum.service.user.service.whitelist;

import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.config.repository.dao.ConfigDao;
import com.github.paicoding.forum.service.config.repository.entity.GlobalConfigDO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthorWhiteListFallbackTest {
    @Test
    @SuppressWarnings("unchecked")
    void redisOutageUsesPersistedMembershipWithoutGrantingUnknownAuthors() {
        Object previousTemplate = ReflectionTestUtils.getField(RedisClient.class, "template");
        Object previousBreaker = ReflectionTestUtils.getField(RedisClient.class, "circuitBreaker");
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("down"));
        ConfigDao configDao = mock(ConfigDao.class);
        GlobalConfigDO config = new GlobalConfigDO();
        config.setValue("1,2");
        when(configDao.getGlobalConfigByKey("auth_article_white_list")).thenReturn(config);
        AuthorWhiteListServiceImpl service = new AuthorWhiteListServiceImpl();
        ReflectionTestUtils.setField(service, "configDao", configDao);
        try {
            RedisClient.register(redis);
            RedisClient.registerCircuitBreaker(null);
            assertTrue(service.authorInArticleWhiteList(1L));
            assertFalse(service.authorInArticleWhiteList(3L));
            when(configDao.getGlobalConfigByKey("auth_article_white_list")).thenReturn(null);
            assertFalse(service.authorInArticleWhiteList(1L));
        } finally {
            ReflectionTestUtils.setField(RedisClient.class, "template", previousTemplate);
            ReflectionTestUtils.setField(RedisClient.class, "circuitBreaker", previousBreaker);
        }
    }
}

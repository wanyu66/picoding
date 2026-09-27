package com.github.paicoding.forum.service.article.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.paicoding.forum.api.model.vo.PageListVo;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.exception.ForumException;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleDTO;
import com.github.paicoding.forum.core.cache.*;
import com.github.paicoding.forum.core.dal.RequestSqlDiagnostics;
import com.github.paicoding.forum.core.senstive.SensitiveService;
import com.github.paicoding.forum.service.article.conveter.ArticleConverter;
import com.github.paicoding.forum.service.article.repository.dao.*;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.repository.mapper.ArticleTagMapper;
import com.github.paicoding.forum.service.article.service.CategoryService;
import com.github.paicoding.forum.service.chatai.bot.AiBots;
import com.github.paicoding.forum.service.config.repository.dao.ConfigDao;
import com.github.paicoding.forum.service.config.repository.mapper.GlobalConfigMapper;
import com.github.paicoding.forum.service.sensitive.service.impl.SensitiveBypassServiceImpl;
import com.github.paicoding.forum.service.statistics.service.impl.CountServiceImpl;
import com.github.paicoding.forum.service.user.repository.dao.*;
import com.github.paicoding.forum.service.user.repository.mapper.UserInfoMapper;
import com.github.paicoding.forum.service.user.service.user.UserServiceImpl;
import com.github.paicoding.forum.service.user.service.whitelist.AuthorWhiteListServiceImpl;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MyBatis SQL and RedisTemplate diagnostics, with H2 and a deterministic Redis connection. */
class ArticleListBatchTest {
    interface FixtureMapper {
        @Select("select * from article order by id desc limit #{size}")
        List<ArticleDO> articles(int size);
    }

    private SqlSession session;
    private JdbcTemplate jdbc;
    private ArticleReadServiceImpl service;
    private ArticleTagDao tags;
    private UserServiceImpl users;
    private SensitiveBypassServiceImpl bypass;
    private CountServiceImpl counts;
    private RedisConnection connection;
    private Object oldTemplate;
    private Object oldBreaker;
    private boolean pipeline;
    private final List<Object> replies = new ArrayList<>();
    private final Set<Long> cacheWhitelist = new HashSet<>();
    private final Set<Long> missingCounts = new HashSet<>();
    private UserFootDao feet;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE", "sa", "");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table article(id bigint, user_id bigint, category_id bigint, title varchar, short_title varchar, summary varchar, source int, create_time timestamp, update_time timestamp)");
        jdbc.execute("create table article_tag(id bigint, article_id bigint, tag_id bigint, deleted int)");
        jdbc.execute("create table tag(id bigint, tag_name varchar, deleted int, status int)");
        jdbc.execute("create table user_info(id bigint, user_id bigint, user_name varchar, photo varchar, position varchar, company varchar, profile varchar, extend varchar, deleted int, user_role int, ip varchar, email varchar, pay_code varchar, create_time timestamp, update_time timestamp)");
        jdbc.execute("create table global_conf(id bigint, `key` varchar, `value` varchar, comment varchar, deleted int)");
        jdbc.update("insert into global_conf values(1,'auth_article_white_list','','',0)");
        for (int i = 1; i <= 8; i++) {
            jdbc.update("insert into article values(?,?,1,'bad title','bad short','bad summary',1,now(),now())", i, 1);
            jdbc.update("insert into user_info(id,user_id,user_name,photo,deleted,user_role) values(?,?,?, 'avatar',0,0)", i, i, "author" + i);
        }
        jdbc.update("insert into tag values(1,'tag one',0,1),(2,'disabled tag',1,0)");
        jdbc.update("insert into article_tag values(1,8,1,0),(2,8,2,0),(3,8,99,0),(4,8,1,1),(5,7,1,0)");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(FixtureMapper.class);
        config.addMapper(UserInfoMapper.class);
        config.addMapper(GlobalConfigMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(db);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/ArticleTagMapper.xml"));
        factory.setPlugins(new RequestSqlDiagnostics());
        session = factory.getObject().openSession(true);
        tags = new ArticleTagDao();
        ReflectionTestUtils.setField(tags, "baseMapper", session.getMapper(ArticleTagMapper.class));
        UserDao userDao = new UserDao();
        ReflectionTestUtils.setField(userDao, "baseMapper", session.getMapper(UserInfoMapper.class));
        users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "userDao", userDao);
        ConfigDao configDao = new ConfigDao();
        ReflectionTestUtils.setField(configDao, "globalConfigMapper", session.getMapper(GlobalConfigMapper.class));
        AuthorWhiteListServiceImpl whitelist = new AuthorWhiteListServiceImpl();
        ReflectionTestUtils.setField(whitelist, "configDao", configDao);
        bypass = new SensitiveBypassServiceImpl();
        AiBots bots = mock(AiBots.class);
        when(bots.aiBots(8L)).thenReturn(true);
        ReflectionTestUtils.setField(bypass, "aiBots", bots);
        ReflectionTestUtils.setField(bypass, "authorWhiteListService", whitelist);
        ReflectionTestUtils.setField(bypass, "userService", users);
        feet = mock(UserFootDao.class);
        counts = new CountServiceImpl(feet);
        service = new ArticleReadServiceImpl();
        ArticleDao articles = mock(ArticleDao.class);
        when(articles.listArticlesByCategoryId(anyLong(), any(PageParam.class))).thenAnswer(call ->
                session.getMapper(FixtureMapper.class).articles((int) ((PageParam) call.getArgument(1)).getPageSize()));
        CategoryService categories = mock(CategoryService.class);
        when(categories.queryCategoryName(anyLong())).thenReturn("category");
        SensitiveService sensitive = mock(SensitiveService.class);
        when(sensitive.replace(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).replace("bad", "***"));
        ReflectionTestUtils.setField(service, "articleDao", articles);
        ReflectionTestUtils.setField(service, "articleTagDao", tags);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "categoryService", categories);
        ReflectionTestUtils.setField(service, "sensitiveService", sensitive);
        ReflectionTestUtils.setField(service, "sensitiveBypassService", bypass);
        ReflectionTestUtils.setField(service, "countService", counts);
        oldTemplate = ReflectionTestUtils.getField(RedisClient.class, "template");
        oldBreaker = ReflectionTestUtils.getField(RedisClient.class, "circuitBreaker");
        connection = mock(RedisConnection.class);
        when(connection.isPipelined()).thenAnswer(call -> pipeline);
        doAnswer(call -> { pipeline = true; replies.clear(); return null; }).when(connection).openPipeline();
        when(connection.closePipeline()).thenAnswer(call -> { pipeline = false; return new ArrayList<>(replies); });
        when(connection.hGetAll(any(byte[].class))).thenAnswer(call -> {
            String key = new String(call.getArgument(0), StandardCharsets.UTF_8);
            long id = Long.parseLong(key.substring(key.lastIndexOf('_') + 1));
            Map<byte[], byte[]> value = new LinkedHashMap<>();
            if (!missingCounts.contains(id)) value.put("readCount".getBytes(StandardCharsets.UTF_8), String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            if (pipeline) { replies.add(value); return null; }
            return value;
        });
        when(connection.sIsMember(any(byte[].class), any(byte[].class))).thenAnswer(call ->
                cacheWhitelist.contains(Long.valueOf(new String(call.getArgument(1), StandardCharsets.UTF_8))));
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        when(redisFactory.getConnection()).thenReturn(connection);
        StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
        new RedisConnectionDiagnostics().postProcessAfterInitialization(redis, "testRedis");
        RedisClient.register(redis);
        RedisClient.registerCircuitBreaker(null);
    }

    @AfterEach
    void cleanup() {
        RequestIoStats.finish();
        ReflectionTestUtils.setField(RedisClient.class, "template", oldTemplate);
        ReflectionTestUtils.setField(RedisClient.class, "circuitBreaker", oldBreaker);
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("drop all objects");
    }

    private PageListVo<ArticleDTO> legacy(int size) {
        List<ArticleDTO> list = new ArrayList<>();
        for (ArticleDO record : session.getMapper(FixtureMapper.class).articles(size)) {
            ArticleDTO dto = ArticleConverter.toDto(record);
            dto.getCategory().setCategory("category");
            dto.setTags(tags.queryArticleTagDetails(record.getId()));
            dto.setCount(counts.queryArticleStatisticInfo(record.getId()));
            com.github.paicoding.forum.api.model.vo.user.dto.BaseUserInfoDTO author = users.queryBasicUserInfo(record.getUserId());
            dto.setAuthorName(author.getUserName());
            dto.setAuthorAvatar(author.getPhoto());
            if (!bypass.shouldBypassByUserId(record.getUserId())) {
                dto.setTitle(dto.getTitle().replace("bad", "***"));
                dto.setShortTitle(dto.getShortTitle().replace("bad", "***"));
                dto.setSummary(dto.getSummary().replace("bad", "***"));
            }
            list.add(dto);
        }
        return PageListVo.newVo(list, size);
    }

    private PageListVo<ArticleDTO> optimized(int size) {
        return service.queryArticlesByCategory(0L, PageParam.newPageInstance(1, size));
    }

    @Test
    void measuredBeforeAfterAndWholeResponseEquality() {
        for (int size : new int[]{1, 4, 8}) {
            RequestIoStats.begin();
            PageListVo<ArticleDTO> before = legacy(size);
            String beforeIo = RequestIoStats.finish();
            assertThat(beforeIo).contains("request/article_tag=" + size, "request/user_info=" + 2 * size,
                    "request/global_conf=" + size, "redisAcquisitions=" + 2 * size + ",");
            RequestIoStats.begin();
            PageListVo<ArticleDTO> after = optimized(size);
            String afterIo = RequestIoStats.finish();
            assertThat(after).usingRecursiveComparison().isEqualTo(before);
            assertThat(afterIo).contains("request/article=1", "article.tags/article_tag=1", "article.author/user_info=1",
                    "article.sensitive/global_conf=1", "article.sensitive/sIsMember=1",
                    "article.statistics/hGetAll=" + size, "redisAcquisitions=2,");
            System.out.println("LIST_BATCH size=" + size + " before=" + beforeIo + " after=" + afterIo);
        }
    }

    @Test
    void mixedAuthorsRolesWhitelistBotsAndTagsPreserveResponse() {
        jdbc.update("update article set user_id=id");
        jdbc.update("update user_info set user_role=1 where user_id=2");
        jdbc.update("update user_info set user_role=2 where user_id=3");
        jdbc.update("update global_conf set `value`='4'");
        cacheWhitelist.add(5L);
        PageListVo<ArticleDTO> before = legacy(8);
        RequestIoStats.begin();
        PageListVo<ArticleDTO> after = optimized(8);
        String io = RequestIoStats.finish();
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
        assertThat(io).contains("article.sensitive/global_conf=1", "article.sensitive/sIsMember=7", "redisAcquisitions=8,");
        assertThat(after.getList().get(0).getTags()).extracting("tag").containsExactly("tag one", "disabled tag", null);
        assertThat(after.getList()).extracting(ArticleDTO::getArticleId).containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        jdbc.update("update global_conf set `value`='1'");
        assertThat(optimized(8).getList().get(7).getTitle()).isEqualTo("bad title");
    }

    @Test
    void emptyPageDoesNoEnrichmentAndMissingAuthorStillFails() {
        RequestIoStats.begin();
        assertThat(service.buildArticleListVo(Collections.emptyList(), 4).getHasMore()).isFalse();
        assertThat(RequestIoStats.finish()).contains("sql={}, redisCommands={}, redisAcquisitions=0");
        jdbc.update("update article set user_id=99 where id=7");
        ForumException beforeError = (ForumException) catchThrowable(() -> legacy(4));
        ForumException afterError = (ForumException) catchThrowable(() -> optimized(4));
        assertThat(afterError.getStatus()).usingRecursiveComparison().isEqualTo(beforeError.getStatus());
        jdbc.update("update article set user_id=1 where id=7");
        assertThat(optimized(1).getHasMore()).isTrue();
        assertThat(optimized(10).getHasMore()).isFalse();
        jdbc.update("delete from user_info");
        beforeError = (ForumException) catchThrowable(() -> legacy(4));
        afterError = (ForumException) catchThrowable(() -> optimized(4));
        assertThat(afterError.getStatus()).usingRecursiveComparison().isEqualTo(beforeError.getStatus());
    }

    @Test
    void cacheMissAndOutageKeepDatabaseFallbackAndWhitelistClosed() {
        missingCounts.add(7L);
        com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO persisted =
                new com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO();
        persisted.setReadCount(123);
        persisted.setPraiseCount(9);
        when(feet.countArticleByArticleId(7L)).thenReturn(persisted);
        com.github.paicoding.forum.service.comment.service.CommentReadService comments =
                mock(com.github.paicoding.forum.service.comment.service.CommentReadService.class);
        when(comments.queryCommentCount(7L)).thenReturn(4);
        ReflectionTestUtils.setField(counts, "commentReadService", comments);
        assertThat(optimized(4)).usingRecursiveComparison().isEqualTo(legacy(4));
        verify(feet, times(2)).countArticleByArticleId(7L);
        jdbc.update("update global_conf set `value`='1'");
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(connection).openPipeline();
        when(connection.hGetAll(any(byte[].class))).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(connection.sIsMember(any(byte[].class), any(byte[].class))).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        assertThat(optimized(4)).usingRecursiveComparison().isEqualTo(legacy(4));
        jdbc.update("update global_conf set `value`=''");
        assertThat(optimized(4).getList()).extracting(ArticleDTO::getTitle).containsOnly("*** title");
    }

    @Test
    void searchOrderHighlightsDuplicateIdsAndTopListRemainUnchanged() {
        com.github.paicoding.forum.service.article.service.search.ArticleSearchService search =
                mock(com.github.paicoding.forum.service.article.service.search.ArticleSearchService.class);
        com.github.paicoding.forum.service.article.service.search.ArticleSearchResult hits =
                new com.github.paicoding.forum.service.article.service.search.ArticleSearchResult();
        hits.setArticleIds(Arrays.asList(2L, 99L, 8L, 2L));
        hits.setHighlights(Collections.singletonMap(8L, "<em>bad</em>"));
        when(search.searchOnlineArticleIds(eq("bad"), any())).thenReturn(hits);
        ReflectionTestUtils.setField(service, "articleSearchService", search);
        ReflectionTestUtils.setField(service, "searchMaxPage", 50L);
        ArticleDao dao = (ArticleDao) ReflectionTestUtils.getField(service, "articleDao");
        when(dao.listByIds(anyCollection())).thenReturn(session.getMapper(FixtureMapper.class).articles(8));
        PageListVo<ArticleDTO> result = service.queryArticlesBySearchKey("bad", PageParam.newPageInstance(1, 4));
        assertThat(result.getList()).extracting(ArticleDTO::getArticleId).containsExactly(2L, 8L, 2L);
        assertThat(result.getList().get(1).getSearchHit()).isEqualTo("<em>bad</em>");
        assertThat(result.getHasMore()).isFalse();
        assertThat(service.queryTopArticlesByCategory(0L)).usingRecursiveComparison()
                .isEqualTo(legacy(PageParam.TOP_PAGE_SIZE.intValue()).getList());
    }

    @Test
    void allDistinctAuthorsStillUseOneUserAndConfigurationQuery() {
        jdbc.update("update article set user_id=id+10");
        jdbc.update("update user_info set user_id=user_id+10");
        PageListVo<ArticleDTO> before = legacy(8);
        RequestIoStats.begin();
        assertThat(optimized(8)).usingRecursiveComparison().isEqualTo(before);
        String io = RequestIoStats.finish();
        assertThat(io).contains("article.author/user_info=1", "article.sensitive/global_conf=1",
                "article.sensitive/sIsMember=8", "article.statistics/hGetAll=8", "redisAcquisitions=9,");
        System.out.println("LIST_BATCH distinctAuthors=8 after=" + io);
    }

    @Test
    void openCircuitFallsBackWithoutAcquiringRedisConnections() {
        CacheProtectionProperties properties = new CacheProtectionProperties();
        properties.setBreakerFailureThreshold(1);
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(properties);
        catchThrowable(() -> breaker.execute(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("down");
        }));
        RedisClient.registerCircuitBreaker(breaker);
        PageListVo<ArticleDTO> before = legacy(4);
        RequestIoStats.begin();
        assertThat(optimized(4)).usingRecursiveComparison().isEqualTo(before);
        assertThat(RequestIoStats.finish()).contains("redisCommands={}, redisAcquisitions=0");
    }
}

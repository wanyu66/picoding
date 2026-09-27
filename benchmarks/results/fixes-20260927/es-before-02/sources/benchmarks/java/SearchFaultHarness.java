import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.core.cache.RedisClient;
import org.springframework.data.redis.core.RedisTemplate;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.service.impl.ArticleReadServiceImpl;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchResult;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Faults only a loopback HTTP stub. No Spring context, real Redis, DB writes or existing ES outage. */
public class SearchFaultHarness {
    static void set(Object target,String name,Object value) throws Exception {
        Class<?> type=target instanceof ArticleSearchServiceImpl?ArticleSearchServiceImpl.class:ArticleReadServiceImpl.class;
        Field f=type.getDeclaredField(name); f.setAccessible(true);f.set(target,value);
    }
    static void checked(ArticleSearchServiceImpl s,String name) throws Exception {
        Field f=ArticleSearchServiceImpl.class.getDeclaredField(name);f.setAccessible(true);((AtomicBoolean)f.get(s)).set(true);
    }
    public static void main(String[] args) throws Exception {
        RedisClient.register(mock(RedisTemplate.class)); // cache misses, no real Redis side effects
        List<Map<String,Object>> results=new ArrayList<>();
        for(String mode:Arrays.asList("socket_timeout","connection_refused","es_timed_out","failed_shard","cursor_missing","page_over_limit")) {
            AtomicInteger requests=new AtomicInteger();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            ExecutorService executor=Executors.newCachedThreadPool();server.setExecutor(executor);
            server.createContext("/", exchange->{
                requests.incrementAndGet();
                try {
                    if(mode.equals("socket_timeout")) Thread.sleep(3500);
                    String response="{\"took\":1,\"timed_out\":"+mode.equals("es_timed_out")+",\"_shards\":{\"total\":1,\"successful\":1,\"failed\":"+(mode.equals("failed_shard")?1:0)+"},\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}";
                    byte[] bytes=response.getBytes("UTF-8");exchange.getResponseHeaders().add("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Exception ignored){}finally{exchange.close();}
            });
            int port=server.getAddress().getPort();server.start();
            if(mode.equals("connection_refused"))server.stop(0);
            try(RestHighLevelClient client=new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1",port,"http")))) {
                ArticleSearchServiceImpl service=spy(new ArticleSearchServiceImpl());
                DefaultListableBeanFactory factory=new DefaultListableBeanFactory();
                factory.registerSingleton("es",client);factory.registerSingleton("metrics",new SimpleMeterRegistry());
                set(service,"restHighLevelClientProvider",factory.getBeanProvider(RestHighLevelClient.class));
                set(service,"meterRegistryProvider",factory.getBeanProvider(MeterRegistry.class));
                set(service,"objectMapper",new ObjectMapper());set(service,"openES",true);
                set(service,"articleAlias","bench_fault_only");set(service,"articleIndex","bench_fault_only");
                set(service,"searchMaxPage",50);set(service,"searchTimeoutMs",1500);set(service,"searchConnectionRequestTimeoutMs",200);
                set(service,"circuitFailureThreshold",5);set(service,"circuitOpenSeconds",30L);
                set(service,"searchCacheTtlSeconds",120L);set(service,"searchCursorTtlSeconds",300L);
                set(service,"fullIndexAvailable",true);checked(service,"indexChecked");checked(service,"indexCoverageChecked");
                doNothing().when(service).syncOnlineKeyword(anyString(),any(PageParam.class));
                ArticleDao dao=mock(ArticleDao.class);
                when(dao.listArticlesByTitleSearchKey(anyString(),any(PageParam.class))).thenReturn(Collections.emptyList());
                ArticleReadServiceImpl read=new ArticleReadServiceImpl();set(read,"articleSearchService",service);set(read,"articleDao",dao);set(read,"searchMaxPage",50L);
                long page=mode.equals("cursor_missing")?2L:mode.equals("page_over_limit")?51L:1L;
                PageParam param=PageParam.newPageInstance(page,10L);
                Map<String,Object> row=new LinkedHashMap<>();row.put("case",mode);row.put("timeoutMs",1500);row.put("page",page);
                List<Double> durations=new ArrayList<>();List<Boolean> nullResults=new ArrayList<>();
                for(int n=0;n<3;n++) {
                    long start=System.nanoTime();ArticleSearchResult result=service.searchOnlineArticleIds("bench fault keyword",param);
                    durations.add((System.nanoTime()-start)/1e6);nullResults.add(result==null);
                }
                read.queryArticlesBySearchKey("bench fault keyword",param);
                long calls=mockingDetails(dao).getInvocations().stream().filter(i->i.getMethod().getName().equals("listArticlesByTitleSearchKey")).count();
                boolean expectFallback=!(mode.equals("cursor_missing")||mode.equals("page_over_limit"));
                row.put("durationMs",durations);row.put("nullResults",nullResults);row.put("mysqlDaoCalls",calls);row.put("stubRequests",requests.get());
                boolean exercised=mode.equals("connection_refused")?String.valueOf(service.runtimeStatus().getLastFailure()).toLowerCase(Locale.ROOT).contains("refused"):
                    mode.equals("socket_timeout")?requests.get()>=3&&durations.stream().allMatch(d->d>=1400):
                    mode.equals("es_timed_out")||mode.equals("failed_shard")?requests.get()>=4:requests.get()==0;
                row.put("faultExercised",exercised);
                row.put("expectedFallback",expectFallback);row.put("pass",exercised&&(expectFallback?calls==1&&nullResults.stream().allMatch(Boolean::booleanValue):calls==0&&nullResults.stream().noneMatch(Boolean::booleanValue)));
                row.put("runtimeStatus",service.runtimeStatus());
                row.put("scope","real search/read services; loopback HTTP fault; DAO mocked empty; sync bootstrap disabled; Redis template mocked cache misses");
                results.add(row);
            }finally{server.stop(0);executor.shutdownNow();}
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(args[0]),results);
        if(results.stream().anyMatch(x->!Boolean.TRUE.equals(x.get("pass")))) System.exit(1);
    }
}

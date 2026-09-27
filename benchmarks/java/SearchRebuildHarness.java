import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRebuildTask;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.elasticsearch.client.*;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real executeFullRebuild/bulkIndex/activateRebuiltIndex with deterministic DAO input; local owned alias only. */
public class SearchRebuildHarness {
    public static void main(String[] args) throws Exception {
        String run=args[0],alias=run+"_alias",target=run+"_new",old=run+"_old";
        if(!run.matches("bench_es_[0-9]+"))throw new IllegalArgumentException("Invalid owner");
        if(!args[1].matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+"))throw new IllegalArgumentException("Loopback only");
        ObjectMapper mapper=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        List<ArticleSearchDocumentDTO> documents=new ArrayList<>();
        try(BufferedReader input=Files.newBufferedReader(Paths.get(args[2]),StandardCharsets.UTF_8)) {
            String line;while((line=input.readLine())!=null) {
                Map<String,Object> row=mapper.readValue(line,Map.class);row.put("columnIds","");
                documents.add(mapper.convertValue(row,ArticleSearchDocumentDTO.class));
            }
        }
        try(RestHighLevelClient client=new RestHighLevelClient(RestClient.builder(HttpHost.create(args[1])))) {
            Map mapping=mapper.readValue(EntityUtils.toString(client.getLowLevelClient().performRequest(new Request("GET","/"+old+"/_mapping")).getEntity()),Map.class);
            Map meta=(Map)((Map)((Map)mapping.get(old)).get("mappings")).get("_meta");
            if(!run.equals(meta.get("benchmarkRun")))throw new IllegalStateException("Old index not owned");
            Map aliases=mapper.readValue(EntityUtils.toString(client.getLowLevelClient().performRequest(new Request("GET","/_alias/"+alias)).getEntity()),Map.class);
            if(aliases.size()!=1||!aliases.containsKey(old))throw new IllegalStateException("Unexpected alias target");
            if(client.getLowLevelClient().performRequest(new Request("HEAD","/"+target)).getStatusLine().getStatusCode()!=404)throw new IllegalStateException("Target exists");
            ArticleSearchServiceImpl service=new ArticleSearchServiceImpl();
            RedisClient.register(mock(RedisTemplate.class));
            DefaultListableBeanFactory factory=new DefaultListableBeanFactory();factory.registerSingleton("es",client);
            SearchFaultHarness.set(service,"restHighLevelClientProvider",factory.getBeanProvider(RestHighLevelClient.class));
            SearchFaultHarness.set(service,"objectMapper",mapper);SearchFaultHarness.set(service,"articleAlias",alias);
            SearchFaultHarness.set(service,"articleIndex",old);SearchFaultHarness.set(service,"rebuildBatchSize",200);
            SearchFaultHarness.set(service,"rebuildMaxRetries",3);
            ArticleDao dao=mock(ArticleDao.class);when(dao.countSearchableArticles()).thenReturn((long)documents.size());
            when(dao.listArticleSearchDocumentsAfterId(anyLong(),anyInt())).thenAnswer(invocation->{
                long after=invocation.getArgument(0);int size=invocation.getArgument(1);
                List<ArticleSearchDocumentDTO> rows=new ArrayList<>();
                for(ArticleSearchDocumentDTO d:documents)if(d.getArticleId()>after&&rows.size()<size)rows.add(d);
                return rows;
            });
            SearchFaultHarness.set(service,"articleDao",dao);
            ArticleSearchRebuildTask task=new ArticleSearchRebuildTask();task.setTaskId(run);task.setTargetIndex(target);task.setCreateTime(new Date());
            long start=System.nanoTime();
            SearchContract.call(service,"executeFullRebuild",new Class<?>[]{ArticleSearchRebuildTask.class},task);
            Map<String,Object> result=new LinkedHashMap<>();result.put("task",task);result.put("elapsedMs",(System.nanoTime()-start)/1e6);
            result.put("daoScope","deterministic in-memory fixture, not real MySQL");
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(args[3]),result);
            // Mark only the exact target this harness just created, including failed rebuilds, for safe cleanup.
            if(client.getLowLevelClient().performRequest(new Request("HEAD","/"+target)).getStatusLine().getStatusCode()==200) {
                Request owner=new Request("PUT","/"+target+"/_mapping");
                owner.setJsonEntity(mapper.writeValueAsString(Collections.singletonMap("_meta",Collections.singletonMap("benchmarkRun",run))));
                client.getLowLevelClient().performRequest(owner);
            }
            if(!ArticleSearchRebuildTask.SUCCEEDED.equals(task.getStatus()))System.exit(1);
        }
    }
}

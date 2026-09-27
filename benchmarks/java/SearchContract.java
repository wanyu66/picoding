import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/** Calls the project's actual query builders; never starts Spring or connects to Redis/MySQL. */
public class SearchContract {
    static Object call(Object service, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = service.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true); return m.invoke(service, args);
    }
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map config = mapper.readValue(new File(args[0]), Map.class);
        Object service = new ArticleSearchServiceImpl();
        String[] fields = {"title", "shortTitle", "summary", "content"};
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("indexDefinition", call(service,"indexDefinition",new Class<?>[0]));
        Object filters = call(service,"onlineFilters",new Class<?>[0]);
        Map<String,Object> queries = new LinkedHashMap<>();
        for (Object item : (List)config.get("queries")) {
            Map q = (Map)item;
            Object keyword = call(service,"articleKeywordQuery",new Class<?>[]{String.class,String[].class},q.get("query"),fields);
            Object body = call(service,"buildSearchBody",new Class<?>[]{long.class,int.class,List.class,List.class,String[].class},0L,10,Collections.singletonList(keyword),filters,fields);
            queries.put((String)q.get("id"), body);
        }
        result.put("queries",queries);
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(args[1]),result);
    }
}

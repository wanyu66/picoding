import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.enums.rank.ActivityRankTimeEnum;
import com.github.paicoding.forum.api.model.vo.rank.dto.RankItemDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.SimpleUserInfoDTO;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.rank.repository.dao.ActivityRankEventDao;
import com.github.paicoding.forum.service.rank.repository.mapper.ActivityRankEventMapper;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import com.github.paicoding.forum.service.rank.service.impl.UserActivityRankServiceImpl;
import com.github.paicoding.forum.service.rank.service.model.ActivityScoreBo;
import com.github.paicoding.forum.service.user.service.UserService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Runs real source with independent fixture arithmetic. Never connects to application databases. */
public class RankingHarness {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final Path OUT=Paths.get(System.getenv("BENCH_RESULTS"));
 static final long TIME=System.currentTimeMillis();
 static final String DAY=LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), MONTH=DAY.substring(0,6);
 static final int N=400;
 static JdbcTemplate db; static StringRedisTemplate redis; static UserActivityRankService rank;
 static final List<Map<String,Object>> summaries=new ArrayList<>();
 @Configuration @EnableTransactionManagement(proxyTargetClass=true)
 @MapperScan(basePackageClasses=ActivityRankEventMapper.class)
 static class Config {
  @Bean(destroyMethod="close") HikariDataSource dataSource(){HikariDataSource d=new HikariDataSource();d.setJdbcUrl("jdbc:mysql://127.0.0.1:13426/bench_ranking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");d.setUsername("root");d.setPassword(System.getenv("BENCH_TEST_PASSWORD"));d.setMaximumPoolSize(220);d.setMinimumIdle(10);return d;}
  @Bean JdbcTemplate jdbc(DataSource d){return new JdbcTemplate(d);}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean SqlSessionFactory factory(DataSource d)throws Exception{MybatisSqlSessionFactoryBean f=new MybatisSqlSessionFactoryBean();f.setDataSource(d);MybatisConfiguration c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);f.setConfiguration(c);return f.getObject();}
  @Bean ActivityRankEventDao dao(){return new ActivityRankEventDao();}
  @Bean UserActivityRankService rank(){return new UserActivityRankServiceImpl();}
  @Bean UserService users(){return (UserService)java.lang.reflect.Proxy.newProxyInstance(UserService.class.getClassLoader(),new Class[]{UserService.class},(p,m,a)->{
   if(m.getName().equals("batchQuerySimpleUserInfo")){List<SimpleUserInfoDTO> list=new ArrayList<>();for(Object id:(Collection<?>)a[0])list.add(new SimpleUserInfoDTO().setUserId((Long)id));return list;}
   if(m.getName().equals("toString"))return "SyntheticUserProfiles";
   if(m.getName().equals("hashCode"))return System.identityHashCode(p);
   if(m.getName().equals("equals"))return p==a[0];throw new IllegalStateException(m.toString());});}
  @Bean(destroyMethod="destroy") LettuceConnectionFactory redisFactory(){return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16409),LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).shutdownTimeout(Duration.ZERO).build());}
  @Bean StringRedisTemplate redis(LettuceConnectionFactory f){StringRedisTemplate r=new StringRedisTemplate(f);RedisClient.register(r);RedisClient.registerCircuitBreaker(null);return r;}
 }
 static class Input {
  public String id,original,action; public long uid,article,time; public boolean active;
  Input(String id,long uid,long article,String action,boolean active,String original){this.id=id;this.uid=uid;this.article=article;this.action=action;this.active=active;this.original=original;this.time=TIME;}
  void apply(){ActivityScoreBo b=new ActivityScoreBo().setEventId(id).setEventTime(time).setArticleId(article).setOriginalEventId(original);if(action.equals("collect"))b.setCollect(active);else b.setPraise(active);rank.addActivityScore(uid,b);}
 }
 interface Work {void apply(int i)throws Exception;}
 static Map<String,Object> load(String name,int c,int n,Work work)throws Exception{
  ExecutorService pool=Executors.newFixedThreadPool(c);CountDownLatch ready=new CountDownLatch(c),go=new CountDownLatch(1);AtomicInteger next=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
  List<Map<String,Object>> raw=Collections.synchronizedList(new ArrayList<>());List<Future<?>> futures=new ArrayList<>();
  for(int t=0;t<c;t++)futures.add(pool.submit(()->{ready.countDown();try{go.await();for(int i;(i=next.getAndIncrement())<n;){Map<String,Object> row=new LinkedHashMap<>();row.put("index",i);long start=System.nanoTime();peak.accumulateAndGet(active.incrementAndGet(),Math::max);try{work.apply(i);row.put("ok",true);}catch(Exception e){row.put("ok",false);row.put("error",e.getClass().getName()+": "+e.getMessage());}finally{active.decrementAndGet();row.put("ms",ms(start));raw.add(row);}}}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
  if(!ready.await(30,TimeUnit.SECONDS))throw new IllegalStateException("worker start timeout");long begin=System.nanoTime();go.countDown();try{for(Future<?> f:futures)f.get(180,TimeUnit.SECONDS);}finally{pool.shutdownNow();}double elapsed=ms(begin);
  raw.sort(Comparator.comparingInt(x->(Integer)x.get("index")));write(name+"-latencies.json",raw);
  List<Double> lat=raw.stream().map(x->(Double)x.get("ms")).sorted().collect(Collectors.toList());long ok=raw.stream().filter(x->Boolean.TRUE.equals(x.get("ok"))).count();
  Map<String,Object> r=new LinkedHashMap<>();r.put("scenario",name);r.put("concurrency",c);r.put("peakInFlight",peak.get());r.put("operations",n);r.put("durationMs",elapsed);r.put("attemptTPS",n*1000/elapsed);r.put("successTPS",ok*1000/elapsed);r.put("updateSuccessRate",ok/(double)n);r.put("p50Ms",pct(lat,.50));r.put("p95Ms",pct(lat,.95));r.put("p99Ms",pct(lat,.99));return r;
 }
 static double pct(List<Double> a,double p){return a.get(Math.max(0,(int)Math.ceil(a.size()*p)-1));}
 static double ms(long t){return (System.nanoTime()-t)/1e6;}
 static void write(String name,Object o)throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve(name).toFile(),o);}
 static void reset()throws Exception{
  if(!"bench_ranking".equals(db.queryForObject("select database()",String.class)))throw new IllegalStateException("unsafe schema");
  db.execute("DELETE FROM activity_rank_event");clearRedis();
 }
 static void clearRedis(){Set<String> keys=redis.keys("pai_activity_rank_*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
 static Map<Long,Integer> scores(String period){Map<Long,Integer> s=new TreeMap<>();redis.opsForZSet().rangeWithScores("pai_activity_rank_"+period+":"+(period.equals("day")?DAY:MONTH),0,-1).forEach(x->s.put(Long.valueOf(x.getValue()),x.getScore().intValue()));return s;}
 static int differences(Map<Long,Integer>a,Map<Long,Integer>b){Set<Long> ids=new HashSet<>(a.keySet());ids.addAll(b.keySet());int n=0;for(Long id:ids)if(!Objects.equals(a.getOrDefault(id,0),b.getOrDefault(id,0)))n++;return n;}
 static List<Long> expectedOrder(Map<Long,Integer> expected){return expected.keySet().stream().sorted(Comparator.<Long>comparingInt(expected::get).reversed().thenComparingLong(Long::longValue)).collect(Collectors.toList());}
 static List<Long> actualOrder(ActivityRankTimeEnum period){return rank.queryRankList(period,10000).stream().map(x->x.getUser().getUserId()).collect(Collectors.toList());}
 static int pending(){return db.queryForObject("select count(*) from activity_rank_event where redis_applied=0",Integer.class);}
 static void compose(String action)throws Exception{Process p=new ProcessBuilder("docker","compose","-p",System.getenv("BENCH_RUN"),"-f","benchmarks/config/ranking-compose.yml",action,"redis").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(OUT.resolve("fault-docker.log").toFile())).start();if(!p.waitFor(60,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IllegalStateException("redis "+action);}
 static void awaitRedis()throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(true){try{if("PONG".equals(redis.getConnectionFactory().getConnection().ping()))return;}catch(Exception ignored){}if(System.nanoTime()>deadline)throw new IllegalStateException("Redis did not recover");Thread.sleep(200);}}
 static void scenario(int c,String type)throws Exception{
  reset();String name=c+"-"+type;int count=type.equals("redis_outage")?c:N;
  List<Input> inputs=new ArrayList<>(),setup=new ArrayList<>();Map<Long,Integer> expected=new TreeMap<>();
  for(int i=0;i<count;i++){
   long uid=type.equals("repeat_like")||type.equals("duplicate_event")||type.equals("hot_member")?1:i+1;
   String id=type.equals("duplicate_event")?"same-event":"event-"+i;
   String action=type.equals("collect_cancel")?"collect":"praise";
   boolean cancel=type.equals("like_cancel")||type.equals("collect_cancel");
   if(cancel)setup.add(new Input("original-"+i,uid,42,action,true,null));
   inputs.add(new Input(id,uid,type.equals("hot_member")?i+1:42,action,!cancel,cancel?"original-"+i:null));
   // Independent business oracle: one active like per user/article; repeated event IDs do not add state.
   if(!cancel)expected.put(uid,type.equals("hot_member")?2*(i+1):2);
  }
  write(name+"-fixture.json",Arrays.asList(setup,inputs));write(name+"-expected.json",expected);
  for(Input i:setup)i.apply();boolean stopped=false;Map<String,Object> r;
  try{if(type.equals("redis_outage")){compose("stop");stopped=true;}
   r=load(name,c,count,i->inputs.get(i).apply());
   // Preserve the server-side deadlock graph; exception text alone cannot identify lock order.
   if(((Double)r.get("updateSuccessRate"))<1)write(name+"-innodb-status.json",db.queryForList("SHOW ENGINE INNODB STATUS"));
   r.put("pendingBeforeRecovery",pending());r.put("scoresImmediatelyAfterWrites",scoresSafe(stopped));
   if(stopped){compose("start");stopped=false;awaitRedis();long begin=System.nanoTime();int loops=0;do{((UserActivityRankServiceImpl)rank).recoverPendingEvents();if(++loops>10)throw new IllegalStateException("pending recovery stuck");}while(pending()>0);r.put("compensationMs",ms(begin));}
  }finally{if(stopped){compose("start");awaitRedis();}}
  Map<Long,Integer> before=scores("day"),monthBefore=scores("month");List<Long> beforeOrder=actualOrder(ActivityRankTimeEnum.DAY),beforeMonthOrder=actualOrder(ActivityRankTimeEnum.MONTH);
  r.put("expected",expected);r.put("beforeScores",before);r.put("beforeMonthScores",monthBefore);r.put("beforeOrder",beforeOrder);r.put("expectedOrder",expectedOrder(expected));
  r.put("ledgerRows",db.queryForObject("select count(*) from activity_rank_event",Integer.class));
  write(name+"-mysql-ledger.json",db.queryForList("select * from activity_rank_event order by id"));
  int diff=differences(expected,before),monthDiff=differences(expected,monthBefore);r.put("scoreDifferenceCount",diff);r.put("monthScoreDifferenceCount",monthDiff);
  int duplicate=0;for(Long id:before.keySet())duplicate+=Math.max(0,before.get(id)-expected.getOrDefault(id,0))/2;r.put("duplicateScoreCount",duplicate);
  clearRedis();long start=System.nanoTime();rank.rebuildRank(ActivityRankTimeEnum.MONTH);r.put("rebuildMs",ms(start));
  Map<Long,Integer> after=scores("day"),monthAfter=scores("month");List<Long> afterOrder=actualOrder(ActivityRankTimeEnum.DAY),afterMonthOrder=actualOrder(ActivityRankTimeEnum.MONTH);
  r.put("afterScores",after);r.put("afterMonthScores",monthAfter);r.put("afterOrder",afterOrder);r.put("beforeMonthOrder",beforeMonthOrder);r.put("afterMonthOrder",afterMonthOrder);
  int afterDiff=differences(expected,after);Set<Long> ids=new HashSet<>(expected.keySet());ids.addAll(after.keySet());r.put("recoveryScoreConsistency",ids.isEmpty()?1:1-afterDiff/(double)ids.size());r.put("beforeAfterIdentical",before.equals(after)&&monthBefore.equals(monthAfter)&&beforeOrder.equals(afterOrder)&&beforeMonthOrder.equals(afterMonthOrder));r.put("pendingAfterRecovery",pending());
  boolean passed=diff==0&&monthDiff==0&&afterDiff==0&&differences(expected,monthAfter)==0&&beforeOrder.equals(expectedOrder(expected))&&afterOrder.equals(expectedOrder(expected))&&beforeMonthOrder.equals(expectedOrder(expected))&&afterMonthOrder.equals(expectedOrder(expected))&&((Double)r.get("updateSuccessRate"))==1&&pending()==0;
  if(type.equals("redis_outage"))passed&=((Integer)r.get("pendingBeforeRecovery"))==count;
  r.put("passed",passed);write(name+"-summary.json",r);summaries.add(r);System.out.println(name+" passed="+passed);
 }
 static Object scoresSafe(boolean stopped){return stopped?"N/A: Redis offline":scores("day");}
 static void luaCase(int c,String mode)throws Exception{
  boolean atomic=!mode.equals("client_rmw_baseline"), duplicate=mode.equals("lua_duplicate");
  reset();String name=c+"-"+mode;java.lang.reflect.Field f=UserActivityRankServiceImpl.class.getDeclaredField("APPLY_SCORE_LUA");f.setAccessible(true);String script=(String)f.get(null);
  List<String> keys=Arrays.asList("activity_rank_events:"+MONTH,"activity_rank_day:"+DAY,"activity_rank_month:"+MONTH,"activity_rank_day:first:"+DAY,"activity_rank_month:first:"+MONTH,"activity_rank_day:ready:"+DAY,"activity_rank_month:ready:"+MONTH);
  String expire=Long.toString(System.currentTimeMillis()/1000+86400);
  Map<String,Object> r=load(name,c,N,i->{if(atomic)RedisClient.executeLongScript(script,keys,duplicate?"same-lua-event":"lua-"+i,"","1","2",Long.toString(TIME),expire,expire,"A|2|1|praise|42");else{Double old=redis.opsForZSet().score("pai_activity_rank_day:"+DAY,"1");redis.opsForZSet().add("pai_activity_rank_day:"+DAY,"1",(old==null?0:old)+2);}});
  int expected=duplicate?2:N*2;
  r.put("expectedScore",expected);r.put("actualScores",scores("day"));r.put("scoreDifferenceCount",scores("day").getOrDefault(1L,0)==expected?0:1);r.put("duplicateScoreCount",Math.max(0,scores("day").getOrDefault(1L,0)-expected)/2);r.put("passed",scores("day").getOrDefault(1L,0)==expected&&((Double)r.get("updateSuccessRate"))==1);r.put("scope",atomic?"Actual Lua invoked concurrently, bypassing JVM rebuildMonitor; includes dedup/day/month metadata":"Illustrative unsafe client ZSCORE+ZADD; fewer side effects, NOT historical production baseline");write(name+"-summary.json",r);summaries.add(r);
 }
 public static void main(String[] args)throws Exception{
  ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.ERROR);
  try(AnnotationConfigApplicationContext ctx=new AnnotationConfigApplicationContext(Config.class)){
   db=ctx.getBean(JdbcTemplate.class);redis=ctx.getBean(StringRedisTemplate.class);rank=ctx.getBean(UserActivityRankService.class);
   db.execute(new String(Files.readAllBytes(Paths.get("paicoding-web/src/main/resources/liquibase/data/update_schema_260822.sql")),StandardCharsets.UTF_8));
   Map<String,Object> version=new LinkedHashMap<>();version.put("mysql",db.queryForObject("select version()",String.class));version.put("redis",redis.getConnectionFactory().getConnection().info("server"));version.put("timezone",ZoneId.systemDefault().toString());write("server-versions.json",version);
   for(int i=0;i<100;i++)new Input("warm-"+i,i+1,42,"praise",true,null).apply();
   for(int c:new int[]{10,50,100,200}){
    for(String s:new String[]{"multi_user","repeat_like","duplicate_event","like_cancel","collect_cancel","hot_member","redis_outage"})scenario(c,s);
    for(String mode:new String[]{"client_rmw_baseline","lua_atomic","lua_duplicate"})luaCase(c,mode);
   }
  }finally{write("summary.json",summaries);}
  if(summaries.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("passed"))))System.exit(2);
 }
}

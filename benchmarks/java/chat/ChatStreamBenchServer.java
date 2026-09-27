import com.fasterxml.jackson.databind.*;
import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.vo.chat.*;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.chatai.search.AiWebSearchService;
import com.github.paicoding.forum.service.chatai.service.*;
import com.github.paicoding.forum.service.chatai.service.impl.deepseek.*;
import com.github.paicoding.forum.service.llm.*;
import com.github.paicoding.forum.service.sensitive.service.SensitiveAiOptimizeService;
import com.github.paicoding.forum.service.user.service.conf.AiConfig;
import com.github.paicoding.forum.service.user.service.LoginService;
import com.github.paicoding.forum.web.front.chat.helper.*;
import com.github.paicoding.forum.web.front.chat.rest.ChatRestController;
import com.github.paicoding.forum.web.front.chat.stomp.*;
import com.github.paicoding.forum.web.global.GlobalInitService;
import com.sun.net.httpserver.HttpServer;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import okhttp3.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Isolated loopback-only real STOMP stack; no application bootstrap/config/paid provider beans. */
public class ChatStreamBenchServer {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final Path OUT=Paths.get(System.getenv("BENCH_RESULTS"));
 static final List<Map<String,Object>> CALLS=new CopyOnWriteArrayList<>(),HISTORY=new CopyOnWriteArrayList<>();
 static final List<OkHttpClient> CLIENTS=new ArrayList<>();
 static final AtomicInteger ACTIVE=new AtomicInteger(),PEAK=new AtomicInteger();
 static HttpServer model;static ExecutorService modelPool;static AnnotationConfigWebApplicationContext app;
 static final long ORIGIN=System.nanoTime();
 static double now(){return (System.nanoTime()-ORIGIN)/1e6;}
 static Map<String,Object> row(Object...v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(v[i].toString(),v[i+1]);return m;}
 static void field(Object o,String n,Object v){ReflectionTestUtils.setField(o,n,v);}
 static void write(String name,Object data)throws Exception{
  Path target=OUT.resolve(name),temporary=OUT.resolve(name+".tmp");
  JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),data);
  try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
  catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
 }
 @Configuration @EnableWebMvc @EnableWebSocketMessageBroker
 @Import({ChatRestController.class,WsAnswerHelper.class,ChatStreamSessionService.class,ChatGenerationControl.class,WsConnectionRegistry.class,ChatContextBudgeter.class,LLMUsageGuard.class})
 static class Config extends WsChatConfig {
  @Override public void registerStompEndpoints(StompEndpointRegistry registry){
   registry.addEndpoint("/bench/ws/{id}").setHandshakeHandler(new DefaultHandshakeHandler(){
    @Override protected Principal determineUser(ServerHttpRequest r,WebSocketHandler w,Map<String,Object>a){return (Principal)a.get(LoginService.SESSION_KEY);}
   }).addInterceptors(new HandshakeInterceptor(){
    public boolean beforeHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler w,Map<String,Object>a){
     String path=r.getURI().getPath();long uid=Long.parseLong(path.substring(path.lastIndexOf('/')+1));
     ReqInfoContext.ReqInfo info=new ReqInfoContext.ReqInfo();info.setUserId(uid);info.setSession("bench-user-"+uid);
     a.put(LoginService.SESSION_KEY,info);a.put(AuthHandshakeInterceptor.AUTHENTICATED_AT,System.currentTimeMillis());a.put(WsAnswerHelper.AI_SOURCE_PARAM,"DEEP_SEEK");return true;
    }
    public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler w,Exception e){}
   });
  }
  @Bean ObjectMapper mapper(){return JSON;}
  @Bean WsChatProperties props(){WsChatProperties p=new WsChatProperties();p.setModelTimeoutMs(15000);return p;}
  static ChatHistoryService history(){ChatHistoryService h=mock(ChatHistoryService.class);when(h.listHistory(any(),any(),any(),any())).thenAnswer(i->new ArrayList<ChatItemVo>());return h;}
  static SensitiveAiOptimizeService sanitizer(){SensitiveAiOptimizeService s=mock(SensitiveAiOptimizeService.class);when(s.sanitizeChatQuestion(anyString())).thenAnswer(i->i.getArgument(0));return s;}
  @Bean LLMRouteProperties routes(){LLMRouteProperties p=new LLMRouteProperties();p.setCircuitFailureThreshold(1);p.setCircuitOpenSeconds(30);p.setPerMinute(1000);p.setDailyQuota(10000);LLMRouteProperties.Route r=new LLMRouteProperties.Route();r.setPrimaryProvider("mock-primary");r.setFallbackProvider("mock-backup");p.getRoutes().put(LLMScene.CHAT,r);return p;}
  @Bean LLMRouter router(){return new LLMRouter(Arrays.asList(new Provider("mock-primary",AISourceEnum.DEEP_SEEK,"ok",new AtomicInteger()),new Provider("mock-backup",AISourceEnum.ZHI_PU_AI,"ok",new AtomicInteger())));}
  @Bean ChatFacade facade(){return new ChatFacade();}
  @Bean ChatServiceFactory factory(ChatGenerationControl control,ChatHistoryService history,SensitiveAiOptimizeService sanitizer,ChatContextBudgeter budget,LLMUsageGuard guard,LLMRouter router){return new ChatServiceFactory(Arrays.asList(service(false,control,history,sanitizer,budget,guard,router),service(true,control,history,sanitizer,budget,guard,router)));}
  @Bean(destroyMethod="destroy") LettuceConnectionFactory redisFactory(){return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16419));}
  @Bean StringRedisTemplate redis(LettuceConnectionFactory f){StringRedisTemplate r=new StringRedisTemplate(f);RedisClient.register(r);RedisClient.registerCircuitBreaker(null);return r;}
 }
 static DeepSeekChatServiceImpl service(boolean backup,ChatGenerationControl control,ChatHistoryService history,SensitiveAiOptimizeService sanitizer,ChatContextBudgeter budget,LLMUsageGuard guard,LLMRouter router){
  DeepSeekIntegration integration=new DeepSeekIntegration();DeepSeekIntegration.DeepSeekConf conf=new DeepSeekIntegration.DeepSeekConf();
  conf.setApiHost("http://127.0.0.1:"+model.getAddress().getPort()+"/"+(backup?"backup":"primary"));conf.setApiKey("synthetic-local-only");conf.setModel("mock-six-chunks");conf.setTimeout(20L);
  // Refuse any non-loopback request, even if fixtures are accidentally reconfigured.
  Dispatcher dispatcher=new Dispatcher();dispatcher.setMaxRequests(256);dispatcher.setMaxRequestsPerHost(256);
  OkHttpClient client=new OkHttpClient.Builder().dispatcher(dispatcher).readTimeout(20,TimeUnit.SECONDS).addInterceptor(chain->{if(!chain.request().url().host().equals("127.0.0.1"))throw new IOException("Paid/external provider forbidden");return chain.proceed(chain.request());}).build();CLIENTS.add(client);
  field(integration,"deepSeekConf",conf);field(integration,"okHttpClient",client);field(integration,"generationControl",control);field(integration,"aiWebSearchService",mock(AiWebSearchService.class));
  DeepSeekChatServiceImpl s=new DeepSeekChatServiceImpl(){
   public AISourceEnum source(){return backup?AISourceEnum.ZHI_PU_AI:AISourceEnum.DEEP_SEEK;}
   protected int getMaxQaCnt(Long u){return 10000;}protected int queryUserdCnt(Long u){return 0;}protected Long incrCnt(Long u){return 1L;}
   protected void recordChatItem(Long u,ChatItemVo i){HISTORY.add(row("user",u,"question",i.getQuestion(),"answer",i.getAnswer(),"atMs",now()));}
  };
  field(s,"deepSeekIntegration",integration);field(s,"sensitiveAiOptimizeService",sanitizer);field(s,"chatHistoryService",history);field(s,"chatHistoryContextNum",10);field(s,"chatContextBudgeter",budget);field(s,"llmUsageGuard",guard);field(s,"llmRouter",router);return s;
 }
 static class Provider implements LLMProvider {
  final String id,mode;final AISourceEnum source;final AtomicInteger calls;
  Provider(String id,AISourceEnum source,String mode,AtomicInteger calls){this.id=id;this.source=source;this.mode=mode;this.calls=calls;}
  public String id(){return id;}public AISourceEnum source(){return source;}public String modelName(String requested){return "mock";}
  public LLMResponse complete(LLMRequest request)throws Exception{calls.incrementAndGet();if(mode.equals("timeout"))Thread.sleep(1000);if(mode.equals("unavailable"))throw new IOException("Mock 503");LLMResponse r=new LLMResponse();r.setProvider(id);r.setContent("MOCK_OK");return r;}
 }
 static Object routerTest(String mode){
  AtomicInteger primary=new AtomicInteger(),backup=new AtomicInteger();LLMRouteProperties p=new LLMRouteProperties();p.setTimeoutMs(80);p.setTotalTimeoutMs(350);p.setMaxTotalAttempts(3);p.setMaxRetries(1);p.setCircuitFailureThreshold(2);p.setCircuitOpenSeconds(30);
  LLMRouteProperties.Route route=new LLMRouteProperties.Route();route.setPrimaryProvider("primary");route.setFallbackProvider("backup");p.getRoutes().put(LLMScene.CHAT,route);
  LLMRouter r=new LLMRouter(Arrays.asList(new Provider("primary",AISourceEnum.DEEP_SEEK,mode.equals("timeout")?"timeout":"unavailable",primary),new Provider("backup",AISourceEnum.ZHI_PU_AI,mode.equals("template")?"unavailable":"ok",backup)));
  field(r,"properties",p);field(r,"usageGuard",app.getBean(LLMUsageGuard.class));field(r,"meterRegistryProvider",app.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
  double start=now();List<String> results=new ArrayList<>();String error=null;
  try{for(int i=0;i<(mode.equals("circuit")?3:1);i++){LLMResponse value=r.complete(LLMRequest.builder().scene(LLMScene.CHAT).userId(900000L+System.nanoTime()%100000).userPrompt("fixture").fallbackResponse(mode.equals("template")?"MOCK_TEMPLATE":null).build());results.add(value.getProvider()+":"+value.getContent());}}catch(Exception e){error=e.toString();}
  return row("mode",mode,"latencySource","mock","durationMs",now()-start,"primaryCalls",primary.get(),"backupCalls",backup.get(),"results",results,"error",error,"attemptTimeoutMs",80,"totalTimeoutMs",350,"maxTotalAttempts",3);
 }
 static void startModel()throws Exception{
  model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),256);modelPool=Executors.newCachedThreadPool();model.setExecutor(modelPool);
  model.createContext("/",ex->{
   String path=ex.getRequestURI().getPath();
   try{
    if(path.equals("/metrics")){byte[]b=JSON.writeValueAsBytes(row("calls",CALLS,"history",HISTORY,"active",ACTIVE.get(),"peak",PEAK.get()));ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);return;}
    if(path.startsWith("/router/")){byte[]b=JSON.writeValueAsBytes(routerTest(path.substring(8)));ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);return;}
    if(path.equals("/reset")){field(app.getBean(LLMRouter.class),"circuits",new ConcurrentHashMap<>());app.getBean(WsChatProperties.class).setModelTimeoutMs(15000);byte[]b="{}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);return;}
    if(path.equals("/timeout")){app.getBean(WsChatProperties.class).setModelTimeoutMs(1200);ex.sendResponseHeaders(200,2);ex.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));return;}
    JsonNode req=JSON.readTree(ex.getRequestBody());String question="";for(JsonNode m:req.path("messages"))if("user".equals(m.path("role").asText()))question=m.path("content").asText();
    String mode=question.split(":")[0];boolean backup=path.startsWith("/backup");Map<String,Object> call=new ConcurrentHashMap<>();call.putAll(row("question",question,"provider",backup?"backup":"primary","startMs",now(),"chunks",0));CALLS.add(call);PEAK.accumulateAndGet(ACTIVE.incrementAndGet(),Math::max);
    try{
     if(mode.equals("unavailable")&&!backup){ex.sendResponseHeaders(503,-1);call.put("httpStatus",503);return;}
     ex.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");ex.sendResponseHeaders(200,0);OutputStream out=ex.getResponseBody();
     for(int n=1;n<=6;n++){
      Thread.sleep(60);String chunk=String.format("chunk%02d|",n);out.write(("data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""+chunk+"\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));out.flush();call.put("chunks",n);
      if(n==3&&(mode.equals("cancel")||mode.equals("timeout"))){for(int j=0;j<1500;j++){Thread.sleep(20);out.write((":"+String.join("",Collections.nCopies(1024,"x"))+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}return;}
     }
     out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();if(mode.equals("duplicate_done")){out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}call.put("done",true);
    }catch(IOException e){call.put("disconnectMs",now());}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{call.put("endMs",now());ACTIVE.decrementAndGet();}
   }catch(Exception e){e.printStackTrace();}finally{ex.close();}
  });model.start();
 }
 public static void main(String[]args)throws Exception{
  if(!"true".equals(System.getenv("BENCH_MOCK_ONLY")))throw new IllegalStateException("Mock-only guard required");
  ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
  Tomcat tomcat=new Tomcat();startModel();
  try{
   Path base=OUT.resolve("tomcat");Files.createDirectories(base);tomcat.setBaseDir(base.toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
   Context context=tomcat.addContext("",base.toString());context.addServletContainerInitializer(new WsSci(),null);
   app=new AnnotationConfigWebApplicationContext();app.register(Config.class);
   app.addBeanFactoryPostProcessor(bf->{bf.registerSingleton("globalInit",mock(GlobalInitService.class));bf.registerSingleton("aiConfig",mock(AiConfig.class));bf.registerSingleton("history",Config.history());bf.registerSingleton("sanitizer",Config.sanitizer());});
   Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(app)).setLoadOnStartup(1);context.addServletMappingDecoded("/","dispatcher");tomcat.start();
   new SpringUtil().setApplicationContext(app);
   // Initialize the dedicated Redis before handling any test connection.
   app.getBean(StringRedisTemplate.class).getConnectionFactory().getConnection().ping();
   write("server-ready.json",row("wsPort",tomcat.getConnector().getLocalPort(),"modelPort",model.getAddress().getPort(),"mockOnly",true,"chunkDelayMs",60,"chunks",6,"modelTimeoutMs",15000,"auth","synthetic loopback principal","history","in-memory sink","okhttpMaxRequests",256,"okhttpMaxRequestsPerHost",256));
   new BufferedReader(new InputStreamReader(System.in)).readLine();
  }finally{
   write("provider-final.json",row("calls",CALLS,"history",HISTORY,"peak",PEAK.get()));
   for(OkHttpClient c:CLIENTS){c.dispatcher().cancelAll();c.dispatcher().executorService().shutdownNow();c.connectionPool().evictAll();}
   tomcat.stop();tomcat.destroy();if(app!=null)app.close();model.stop(0);modelPool.shutdownNow();
  }
 }
}

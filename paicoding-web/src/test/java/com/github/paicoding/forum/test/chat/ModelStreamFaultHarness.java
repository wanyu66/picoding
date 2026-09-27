package com.github.paicoding.forum.test.chat;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ChatAnswerTypeEnum;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.enums.ai.AiChatStatEnum;
import com.github.paicoding.forum.api.model.vo.chat.*;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.chatai.search.AiWebSearchService;
import com.github.paicoding.forum.service.chatai.service.*;
import com.github.paicoding.forum.service.chatai.service.impl.deepseek.*;
import com.github.paicoding.forum.service.llm.*;
import com.github.paicoding.forum.service.sensitive.service.SensitiveAiOptimizeService;
import com.github.paicoding.forum.web.front.chat.helper.*;
import com.github.paicoding.forum.web.front.chat.stomp.WsChatProperties;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in executable integration harness. Never starts the application or uses production credentials. */
public class ModelStreamFaultHarness {
    static final Path OUT = Paths.get("paicoding-web/target/model-stream-fault");
    static final List<Map<String,Object>> results = new ArrayList<>();
    static final String EXPECTED = "chunk01|chunk02|chunk03|chunk04|chunk05|chunk06|";
    static JdbcTemplate jdbc;
    static StringRedisTemplate redis;
    static final String run = UUID.randomUUID().toString();
    static final AtomicLong users = new AtomicLong(System.currentTimeMillis());

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("model.stream.fault")) throw new IllegalStateException("Set -Dmodel.stream.fault=true");
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        Files.createDirectories(OUT);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16389));
        factory.afterPropertiesSet(); redis = new StringRedisTemplate(factory);
        RedisClient.register(redis); RedisClient.registerCircuitBreaker(null);
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13316/outbox_rank_fault?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "fault-test-only"));
        jdbc.execute("CREATE TABLE IF NOT EXISTS model_stream_fault_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(64), case_id VARCHAR(64), answer TEXT)");
        StaticApplicationContext application=new StaticApplicationContext();
        application.getBeanFactory().registerSingleton("chatFacade",mock(ChatFacade.class));
        application.refresh();new SpringUtil().setApplicationContext(application);
        try {
            test("normal_and_replay", () -> streamCase("normal",0,false));
            test("upstream_eof_after_3", () -> streamCase("eof",3,false));
            test("upstream_error_after_3", () -> streamCase("error",3,false));
            test("token_stall_after_3", () -> streamCase("stall",3,false));
            test("cancel_after_3", () -> streamCase("cancel",3,false));
            test("duplicate_done", () -> streamCase("duplicate_done",0,false));
            test("duplicate_business_callback", () -> streamCase("duplicate_callback",0,false));
            test("cancel_before_hook_registration", ModelStreamFaultHarness::cancelRace);
            test("cancel_registration_races", ModelStreamFaultHarness::cancelRegistrationRaces);
            test("router_total_deadline", () -> routerCase(false));
            test("router_total_attempts", () -> routerCase(true));
            test("router_fallback_success", ModelStreamFaultHarness::routerFallbackSuccess);
            test("router_validation_deadline", () -> validationDeadline(false));
            test("template_validation_deadline", () -> validationDeadline(true));
            String endpoint=System.getenv("PAI_MODEL_TEST_BASE_URL"), key=System.getenv("PAI_MODEL_TEST_API_KEY"), model=System.getenv("PAI_MODEL_TEST_MODEL");
            if (endpoint!=null && key!=null && model!=null) test("real_model_compatibility", () -> streamCase("real",0,true));
            else results.add(row("case","real_model_compatibility","status","BLOCKED","reason","Set PAI_MODEL_TEST_BASE_URL, PAI_MODEL_TEST_API_KEY, PAI_MODEL_TEST_MODEL; no real request made","latencySource","real"));
        } finally {
            Files.write(OUT.resolve("results.json"), JsonUtil.toStr(results).getBytes(StandardCharsets.UTF_8));
            factory.destroy();
            application.close();
        }
        System.out.println(JsonUtil.toStr(results));
        // Acceptance failures are evidence, not a green build. All cases still execute.
        if(results.stream().anyMatch(r -> "FAIL".equals(r.get("status"))||"ERROR".equals(r.get("status")))) System.exit(2);
    }

    static void test(String name, Checked test) {
        try { test.run(); } catch(Throwable e) { results.add(row("case",name,"status","ERROR","reason",e.toString())); }
    }
    interface Checked { void run() throws Exception; }
    static Map<String,Object> row(Object... values) { Map<String,Object> r=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)r.put(values[i].toString(),values[i+1]);return r; }
    static void field(Object target,String name,Object value){ReflectionTestUtils.setField(target,name,value);}
    static void await(String description,BooleanSupplier condition,long ms) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        if(!condition.getAsBoolean()) throw new AssertionError("Timed out: "+description);
    }
    static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}

    static void streamCase(String mode,int after,boolean real) throws Exception {
        final String caseId=mode+"-"+UUID.randomUUID();
        final long userId=users.incrementAndGet();
        try(MockModel server=new MockModel(mode,after)) {
            ChatGenerationControl control=new ChatGenerationControl();
            OkHttpClient client=new OkHttpClient.Builder().readTimeout(real?30:3,TimeUnit.SECONDS).build();
            ChatStreamSessionService sessions=new ChatStreamSessionService();
            WsChatProperties props=new WsChatProperties(); props.setModelTimeoutMs(real?40000:("stall".equals(mode)?1800:5000));
            field(sessions,"properties",props);field(sessions,"generationControl",control);
            try {
                DeepSeekIntegration integration=new DeepSeekIntegration();
                DeepSeekIntegration.DeepSeekConf conf=new DeepSeekIntegration.DeepSeekConf();
                conf.setApiHost(real?System.getenv("PAI_MODEL_TEST_BASE_URL"):server.url());
                conf.setApiKey(real?System.getenv("PAI_MODEL_TEST_API_KEY"):"local-fixture-only");
                conf.setModel(real?System.getenv("PAI_MODEL_TEST_MODEL"):"fixed-six-chunks");
                conf.setTimeout(30L);
                field(integration,"deepSeekConf",conf);field(integration,"okHttpClient",client);
                field(integration,"generationControl",control);field(integration,"aiWebSearchService",mock(AiWebSearchService.class));
                // Production async template and DeepSeek parser; a real SQL sink replaces history DAO wiring.
                DeepSeekChatServiceImpl service=new DeepSeekChatServiceImpl(){
                    public AiChatStatEnum doAsyncAnswer(Long user,ChatRecordsVo response,java.util.function.BiConsumer<AiChatStatEnum,ChatRecordsVo> callback) {
                        if (!"duplicate_callback".equals(mode)) return super.doAsyncAnswer(user,response,callback);
                        response.getRecords().get(0).initAnswer(EXPECTED,ChatAnswerTypeEnum.STREAM_END);
                        ExecutorService workers=Executors.newFixedThreadPool(2);
                        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
                        Runnable finish=()->{ready.countDown();try{go.await();callback.accept(AiChatStatEnum.END,response);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
                        try {
                            Future<?> a=workers.submit(finish),b=workers.submit(finish);
                            if(!ready.await(2,TimeUnit.SECONDS))throw new AssertionError("callbacks not ready");
                            go.countDown();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
                            callback.accept(AiChatStatEnum.ERROR,response);
                            return AiChatStatEnum.IGNORE;
                        } catch(Exception e){throw new IllegalStateException(e);}finally{workers.shutdownNow();}
                    }
                    protected int getMaxQaCnt(Long user){return 100;}
                    protected int queryUserdCnt(Long user){return 0;}
                    protected Long incrCnt(Long user){return 1L;}
                    protected void recordChatItem(Long user,ChatItemVo item){jdbc.update("INSERT INTO model_stream_fault_history(run_id,case_id,answer) VALUES(?,?,?)",run,caseId,item.getAnswer());}
                };
                field(service,"deepSeekIntegration",integration);
                SensitiveAiOptimizeService sanitize=mock(SensitiveAiOptimizeService.class);
                when(sanitize.sanitizeChatQuestion(anyString())).thenAnswer(i->i.getArgument(0));
                ChatHistoryService history=mock(ChatHistoryService.class);
                when(history.listHistory(any(),any(),any(),any())).thenAnswer(i->new ArrayList<ChatItemVo>());
                field(service,"sensitiveAiOptimizeService",sanitize);field(service,"chatHistoryService",history);field(service,"chatHistoryContextNum",10);
                ChatContextBudgeter budget=new ChatContextBudgeter();field(budget,"maxMessageChars",4000);field(budget,"maxInputTokens",3000);
                field(service,"chatContextBudgeter",budget);
                LLMRouteProperties routes=new LLMRouteProperties();
                StaticApplicationContext metrics=new StaticApplicationContext();
                LLMUsageGuard guard=new LLMUsageGuard();field(guard,"properties",routes);field(guard,"meterRegistryProvider",metrics.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
                field(service,"llmUsageGuard",guard);field(service,"llmRouter",mock(LLMRouter.class));
                List<ChatStreamEvent> events=new CopyOnWriteArrayList<>();
                AtomicLong first=new AtomicLong(),start=new AtomicLong();
                WsAnswerHelper helper=new WsAnswerHelper(){public void responseEvent(String session,ChatStreamEvent event){
                    if("delta".equals(event.getEvent())) first.compareAndSet(0,elapsed(start.get()));
                    events.add(JsonUtil.toObj(JsonUtil.toStr(event),ChatStreamEvent.class));
                }};
                ChatFacade facade=mock(ChatFacade.class);
                when(facade.autoChat(eq(AISourceEnum.DEEP_SEEK),anyString(),any())).thenAnswer(i -> service.asyncChat(userId,i.getArgument(1),i.getArgument(2)));
                field(helper,"chatFacade",facade);field(helper,"streamSessions",sessions);field(helper,"generationControl",control);
                ReqInfoContext.ReqInfo info=new ReqInfoContext.ReqInfo();info.setUserId(userId);info.setChatId(caseId);ReqInfoContext.addReqInfo(info);
                ChatStreamRequest request=new ChatStreamRequest();request.setContent("Reply with only OK.");request.setRequestId(caseId);
                start.set(System.nanoTime());
                String generation=helper.sendMsgToUser(AISourceEnum.DEEP_SEEK,caseId,caseId,request);
                long cancelStarted=0;
                if("cancel".equals(mode)) {
                    await("third decoded chunk",()->events.stream().filter(e->"delta".equals(e.getEvent())).count()>=3,5000);
                    cancelStarted=System.nanoTime();helper.cancel(caseId,generation);
                }
                await("terminal event",()->events.stream().anyMatch(ChatStreamEvent::isTerminal),real?45000:6000);
                long totalMs=elapsed(start.get());
                if("cancel".equals(mode)||"stall".equals(mode)) await("upstream socket disconnect",()->server.disconnected.get(),3000);
                Thread.sleep(150); // expose late/duplicate terminal callbacks before inspecting SQL
                long rows=jdbc.queryForObject("SELECT COUNT(*) FROM model_stream_fault_history WHERE run_id=? AND case_id=?",Long.class,run,caseId);
                ChatStreamEvent terminal=events.stream().filter(ChatStreamEvent::isTerminal).findFirst().get();
                String answer=terminal.getPayload()==null?"":terminal.getPayload().getRecords().get(0).getAnswer();
                boolean pass;
                if("normal".equals(mode)||"duplicate_done".equals(mode)||"duplicate_callback".equals(mode))pass=EXPECTED.equals(answer.trim())&&rows==1;
                else if("real".equals(mode))pass="completed".equals(terminal.getEvent())&&rows==1&&!answer.trim().isEmpty()&&!answer.contains("Error:");
                else if("cancel".equals(mode))pass=rows==0&&server.disconnected.get()&&"cancelled".equals(terminal.getEvent());
                else if("stall".equals(mode))pass=rows==0&&server.disconnected.get()&&"timeout".equals(terminal.getEvent());
                else pass=rows==0&&"error".equals(terminal.getEvent())&&("eof".equals(mode)?"UPSTREAM_INCOMPLETE":"MODEL_ERROR").equals(terminal.getErrorCode());
                Map<String,Object> result=row("case",mode,"status",pass?"PASS":"FAIL","latencySource",real?"real":"mock","faultAfterChunk",after,"firstTokenMs",first.get(),"terminalMs",totalMs,"terminalEvent",terminal.getEvent(),"sqlRows",rows);
                result.put("errorCode",terminal.getErrorCode());
                if(!real){result.putAll(row("upstreamRequests",server.requests.get(),"upstreamChunksWritten",server.chunks.get(),"upstreamDisconnectObserved",server.disconnected.get(),"configuredChunkDelayMs",80));}
                if(cancelStarted>0)result.put("cancelToDisconnectMs",TimeUnit.NANOSECONDS.toMillis(server.disconnectNanos.get()-cancelStarted));
                if(!real)result.put("answer",answer);
                if("normal".equals(mode)) {
                    long replayStart=System.nanoTime();
                    List<ChatStreamEvent> replay=sessions.replay(caseId,caseId,generation,4);
                    List<ChatStreamEvent> replayAgain=sessions.replay(caseId,caseId,generation,4);
                    long afterRows=jdbc.queryForObject("SELECT COUNT(*) FROM model_stream_fault_history WHERE run_id=? AND case_id=?",Long.class,run,caseId);
                    boolean replayOk=replay.size()==4 && replay.get(0).getSeq()==5 && replay.get(3).isTerminal() && rows==afterRows;
                    result.put("replayFromSeq",4);result.put("replayedEvents",replay.size());result.put("rowsAfterRepeatedReplay",afterRows);
                    result.put("replayMs",elapsed(replayStart));
                    result.put("unauthorizedReplayEvents",sessions.replay("wrong-owner",caseId,generation,0).size());
                    if(!replayOk)result.put("status","FAIL");
                    Files.write(OUT.resolve("replay-events.json"),JsonUtil.toStr(row("initial",events.subList(0,Math.min(4,events.size())),"replay",replay,"replayAgain",replayAgain,"expected",EXPECTED)).getBytes(StandardCharsets.UTF_8));
                }
                results.add(result);
            } finally { ReqInfoContext.clear();sessions.shutdown();client.dispatcher().cancelAll();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll(); }
        }
    }

    static void cancelRace() {
        ChatGenerationControl control=new ChatGenerationControl();AtomicBoolean cancelled=new AtomicBoolean();
        control.within("race",()->{control.cancel("race");control.registerCancelHook(()->cancelled.set(true));return null;});
        results.add(row("case","cancel_before_hook_registration","status",cancelled.get()?"PASS":"FAIL","upstreamCancellationHookCalled",cancelled.get()));
        control.complete("race");
    }

    static void cancelRegistrationRaces() throws Exception {
        ChatGenerationControl control=new ChatGenerationControl();AtomicInteger fired=new AtomicInteger();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            for(int n=0;n<100;n++) {
                String id="race-"+n;control.prepare(id);CountDownLatch go=new CountDownLatch(1);
                Future<?> register=workers.submit(()->control.within(id,()->{try{go.await();}catch(InterruptedException e){throw new IllegalStateException(e);}control.registerCancelHook(fired::incrementAndGet);return null;}));
                Future<?> cancel=workers.submit(()->{go.countDown();control.cancel(id);});
                register.get(2,TimeUnit.SECONDS);cancel.get(2,TimeUnit.SECONDS);
            }
            control.prepare("before-within");control.cancel("before-within");
            control.within("before-within",()->{control.registerCancelHook(fired::incrementAndGet);return null;});
            control.within("completed",()->{control.complete("completed");control.registerCancelHook(fired::incrementAndGet);return null;});
            Map<?,?> retained=(Map<?,?>)ReflectionTestUtils.getField(control,"generations");
            results.add(row("case","cancel_registration_races","status",fired.get()==102&&retained.isEmpty()?"PASS":"FAIL","cancelHooksCalled",fired.get(),"expected",102,"retainedStates",retained.size()));
        } finally {workers.shutdownNow();}
    }

    static void routerCase(boolean attemptsOnly) throws Exception {
        try(MockModel server=new MockModel("router",0)) {
            List<String> calls=new CopyOnWriteArrayList<>();
            OkHttpClient client=new OkHttpClient.Builder().readTimeout(2,TimeUnit.SECONDS).build();
            List<LLMProvider> providers=new ArrayList<>();
            for(String id:Arrays.asList("primary","fallback"))providers.add(new LLMProvider(){
                public String id(){return id;}
                public AISourceEnum source(){return AISourceEnum.DEEP_SEEK;}
                public String modelName(String model){return model;}
                public LLMResponse complete(LLMRequest request)throws Exception {
                    calls.add(id);
                    try(Response r=client.newCall(new Request.Builder().url(server.url()+"/"+id).build()).execute()) {
                        if(!r.isSuccessful())throw new IOException("fixture HTTP "+r.code());
                        LLMResponse answer=new LLMResponse();answer.setContent(r.body().string());return answer;
                    }
                }
            });
            try {
                LLMRouter router=new LLMRouter(providers);LLMRouteProperties p=new LLMRouteProperties();
                LLMRouteProperties.Route route=new LLMRouteProperties.Route();route.setPrimaryProvider("primary");route.setFallbackProvider("fallback");route.setTimeoutMs(150L);route.setMaxRetries(1);p.getRoutes().put(LLMScene.CHAT,route);
                route.setTotalTimeoutMs(attemptsOnly?5000L:350L);route.setMaxTotalAttempts(attemptsOnly?3:4);
                StaticApplicationContext ctx=new StaticApplicationContext();LLMUsageGuard guard=new LLMUsageGuard();
                field(guard,"properties",p);field(guard,"meterRegistryProvider",ctx.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
                field(router,"properties",p);field(router,"usageGuard",guard);field(router,"meterRegistryProvider",ctx.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
                long start=System.nanoTime();boolean failed=false;
                try{router.complete(LLMRequest.builder().scene(LLMScene.CHAT).userId(users.incrementAndGet()).userPrompt("fixture").build());}catch(IllegalStateException expected){failed=true;}
                long ms=elapsed(start);
                boolean pass=failed&&(attemptsOnly?calls.size()==3:calls.size()<=3)&&ms<=(attemptsOnly?1000:450);
                results.add(row("case",attemptsOnly?"router_total_attempts":"router_total_deadline","status",pass?"PASS":"FAIL","latencySource","mock","attemptTimeoutMs",150,"maxRetriesPerProvider",1,"maxTotalAttempts",route.getMaxTotalAttempts(),"attemptOrder",calls,"observedTotalMs",ms,"totalBudgetMs",route.getTotalTimeoutMs(),"measurementToleranceMs",attemptsOnly?null:100,"totalBudgetConfigSupported",true,"transport","test HTTP provider adapter; production LLMRouter"));
            } finally {client.dispatcher().cancelAll();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
        }
    }

    static void routerFallbackSuccess() {
        List<String> models=new ArrayList<>();List<LLMProvider> providers=new ArrayList<>();
        for(String id:Arrays.asList("primary","fallback"))providers.add(new LLMProvider(){
            public String id(){return id;}
            public AISourceEnum source(){return AISourceEnum.DEEP_SEEK;}
            public String modelName(String requested){return requested;}
            public LLMResponse complete(LLMRequest request)throws Exception {
                models.add(request.getModel());
                if("primary".equals(id))throw new IOException("fixture failure");
                LLMResponse response=new LLMResponse();response.setProvider(id);response.setContent("fallback ok");return response;
            }
        });
        LLMRouteProperties p=new LLMRouteProperties();p.setTotalTimeoutMs(2000);p.setMaxTotalAttempts(3);
        LLMRouteProperties.Route route=new LLMRouteProperties.Route();route.setPrimaryProvider("primary");route.setPrimaryModel("p-model");route.setFallbackProvider("fallback");route.setFallbackModel("f-model");p.getRoutes().put(LLMScene.CHAT,route);
        StaticApplicationContext ctx=new StaticApplicationContext();LLMUsageGuard guard=new LLMUsageGuard();
        field(guard,"properties",p);field(guard,"meterRegistryProvider",ctx.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        LLMRouter router=new LLMRouter(providers);field(router,"properties",p);field(router,"usageGuard",guard);field(router,"meterRegistryProvider",ctx.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        LLMRequest request=LLMRequest.builder().scene(LLMScene.CHAT).userId(users.incrementAndGet()).userPrompt("fixture").model("caller-model").build();
        LLMResponse response=router.complete(request);
        boolean pass="fallback ok".equals(response.getContent())&&"caller-model".equals(request.getModel())&&models.equals(Arrays.asList("p-model","p-model","f-model"));
        results.add(row("case","router_fallback_success","status",pass?"PASS":"FAIL","latencySource","mock","models",models,"callerModel",request.getModel(),"scope","provider test doubles; global budget inheritance and request isolation"));
    }

    static void validationDeadline(boolean template) throws Exception {
        AtomicBoolean interrupted=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
        LLMProvider provider=new LLMProvider(){
            public String id(){return "primary";}
            public AISourceEnum source(){return AISourceEnum.DEEP_SEEK;}
            public String modelName(String requested){return requested;}
            public LLMResponse complete(LLMRequest request){calls.incrementAndGet();LLMResponse r=new LLMResponse();r.setContent("validatable");return r;}
        };
        LLMRouteProperties p=new LLMRouteProperties();p.setTotalTimeoutMs(100);p.setMaxTotalAttempts(4);
        LLMRouteProperties.Route route=new LLMRouteProperties.Route();route.setPrimaryProvider("primary");p.getRoutes().put(LLMScene.CHAT,route);
        LLMRouter router=new LLMRouter(template?Collections.emptyList():Collections.singletonList(provider));
        StaticApplicationContext ctx=new StaticApplicationContext();
        field(router,"properties",p);field(router,"usageGuard",mock(LLMUsageGuard.class));field(router,"meterRegistryProvider",ctx.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        LLMRequest request=LLMRequest.builder().scene(LLMScene.CHAT).userPrompt("fixture").fallbackResponse(template?"template":null).validator(content->{
            try{Thread.sleep(1000);}catch(InterruptedException e){interrupted.set(true);throw e;}
        }).build();
        long start=System.nanoTime();boolean failed=false;
        try{router.complete(request);}catch(IllegalStateException expected){failed=true;}
        long ms=elapsed(start);await("validator interruption",interrupted::get,1000);
        results.add(row("case",template?"template_validation_deadline":"router_validation_deadline","status",failed&&ms<=250&&calls.get()==(template?0:1)?"PASS":"FAIL","observedTotalMs",ms,"totalBudgetMs",100,"providerCalls",calls.get(),"validatorInterrupted",interrupted.get(),"latencySource","mock"));
    }

    /** Deterministic OpenAI-style SSE over a real loopback socket; no LLM SDK is stubbed. */
    static class MockModel implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger requests=new AtomicInteger(),chunks=new AtomicInteger();final AtomicBoolean disconnected=new AtomicBoolean();final AtomicLong disconnectNanos=new AtomicLong();
        MockModel(String mode,int after)throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.incrementAndGet();
                try {
                    while(exchange.getRequestBody().read()!=-1){};
                    if(mode.equals("router")){Thread.sleep(700);exchange.sendResponseHeaders(503,-1);return;}
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);
                    OutputStream out=exchange.getResponseBody();
                    for(int n=1;n<=6;n++) {
                        Thread.sleep(80);
                        out.write(("data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixed\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"chunk0"+n+"|\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));out.flush();chunks.incrementAndGet();
                        if(n==after){
                            if(mode.equals("eof"))return;
                            if(mode.equals("error")){out.write("data: {invalid-json}\n\n".getBytes(StandardCharsets.UTF_8));out.flush();return;}
                            if(mode.equals("stall")||mode.equals("cancel")) {
                                // SSE comments carry no tokens but make cancellation observable at the peer.
                                for(int i=0;i<400;i++){Thread.sleep(20);out.write((":"+String.join("",Collections.nCopies(1024,"x"))+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
                                return;
                            }
                        }
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();
                    if(mode.equals("duplicate_done")){out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}
                } catch(IOException e){disconnected.set(true);disconnectNanos.set(System.nanoTime());}
                  catch(InterruptedException e){Thread.currentThread().interrupt();}
                  finally{exchange.close();}
            });server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
}

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.core.config.RabbitmqProperties;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.notify.config.*;
import com.github.paicoding.forum.service.notify.model.*;
import com.github.paicoding.forum.service.notify.monitor.DomainEventMessageMonitor;
import com.github.paicoding.forum.service.notify.service.impl.*;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class OutboxReliabilityHarness {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String RUN=System.getenv("BENCH_RUN"), CONSUMER="bench-reliability";
    static final Path DIR=Paths.get(System.getenv("BENCH_RESULTS"));
    static final RabbitMqAutoConfig RABBIT=new RabbitMqAutoConfig();
    static final AtomicBoolean POISON=new AtomicBoolean(true);
    static final List<Map<String,Object>> CHECKS=new ArrayList<>();
    static final List<DomainEvent> FIXTURES=new ArrayList<>();
    static JdbcTemplate db; static AnnotationConfigApplicationContext context;
    static SimpleMessageListenerContainer listener;
    static long recoveryStart; static String process="main";

    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    public static class Config {
        @Bean(destroyMethod="close") public HikariDataSource dataSource(){
            HikariDataSource d=new HikariDataSource();d.setJdbcUrl("jdbc:mysql://127.0.0.1:13416/bench_outbox?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            d.setUsername("root");d.setPassword(System.getenv("BENCH_TEST_PASSWORD"));d.setMaximumPoolSize(12);return d;
        }
        @Bean public JdbcTemplate jdbcTemplate(DataSource d){return new JdbcTemplate(d);}
        @Bean public DataSourceTransactionManager transactionManager(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean public DomainEventOutboxServiceImpl outbox(JdbcTemplate d){return new DomainEventOutboxServiceImpl(d);}
        @Bean public DomainEventConsumeService consumer(JdbcTemplate d){return new DomainEventConsumeService(d);}
        @Bean public CachingConnectionFactory connection(){
            RabbitmqProperties p=new RabbitmqProperties();p.setHost("127.0.0.1");p.setPort(15782);p.setUsername("bench");p.setPassport(System.getenv("BENCH_TEST_PASSWORD"));p.setVirtualhost("/");
            CachingConnectionFactory f=(CachingConnectionFactory)RABBIT.rabbitConnectionFactory(p);
            f.getRabbitConnectionFactory().setConnectionTimeout(1500);return f;
        }
        @Bean public RabbitAdmin admin(CachingConnectionFactory c){return new RabbitAdmin(c);}
        @Bean public SimpleMeterRegistry registry(){return new SimpleMeterRegistry();}
        @Bean public DomainEventMessageMonitor monitor(SimpleMeterRegistry r,RabbitAdmin a){return new DomainEventMessageMonitor(r,a);}
        @Bean public RabbitTemplate template(CachingConnectionFactory c,DomainEventMessageMonitor m){return RABBIT.rabbitTemplate(c,JSON,m);}
        @Bean public DomainEventOutboxPublisher publisher(JdbcTemplate d,RabbitTemplate t){return new DomainEventOutboxPublisher(d,t);}
    }
    static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static void save(String file,Object value)throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve(file).toFile(),value);}
    static synchronized void log(String file,Object value){try{Files.write(DIR.resolve(file),(JSON.writeValueAsString(value)+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    static void check(String name,boolean pass,Object evidence){CHECKS.add(map("name",name,"pass",pass,"evidence",evidence));if(!pass)throw new AssertionError(name+": "+evidence);}
    static int count(String sql,Object... args){return db.queryForObject(sql,Integer.class,args);}
    static void await(String label,BooleanSupplier condition,int seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Timeout: "+label);
    }
    static boolean ready(){try{return context.getBean(CachingConnectionFactory.class).createConnection().isOpen();}catch(Exception e){return false;}}
    static void init()throws Exception{
        await("MySQL TCP database readiness",()->{try{return count("SELECT 1")==1;}catch(Exception e){return false;}},90);
        check("dedicated database", "bench_outbox".equals(db.queryForObject("SELECT DATABASE()",String.class)),"bench_outbox:13416");
        check("new empty schema",count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")==0,"no existing data permitted");
        String ddl=new String(Files.readAllBytes(Paths.get("paicoding-web/src/main/resources/liquibase/data/update_schema_260822_2.sql")),StandardCharsets.UTF_8);
        String[] statements=ddl.split(";");
        db.execute(statements[0].replace("interaction_event_outbox","domain_event_outbox"));
        db.execute(statements[1].replace("interaction_event_consume","domain_event_consume"));
        db.execute("CREATE TABLE bench_guard(run_id varchar(64) primary key)");db.update("INSERT INTO bench_guard VALUES(?)",RUN);
        db.execute("CREATE TABLE bench_source(event_id varchar(64) PRIMARY KEY,scenario varchar(32),created_ms bigint,content varchar(100))");
        // No UNIQUE(event_id) on the effect table: a broken idempotency guard must be observable, not hidden.
        db.execute("CREATE TABLE bench_effect(id bigint AUTO_INCREMENT PRIMARY KEY,event_id varchar(64),scenario varchar(32),created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE bench_attempt(attempt_id varchar(64) PRIMARY KEY,event_id varchar(64),scenario varchar(32),delivery_key varchar(160),redelivered boolean,started_ms bigint,finished_ms bigint,handler_entered boolean,outcome varchar(32),error varchar(1000))");
        RabbitAdmin a=context.getBean(RabbitAdmin.class);
        a.declareExchange(RABBIT.interactionExchange());a.declareExchange(RABBIT.interactionDeadLetterExchange());
        a.declareQueue(RABBIT.interactionQueue());a.declareQueue(RABBIT.interactionDeadLetterQueue());
        a.declareBinding(RABBIT.interactionBinding());a.declareBinding(RABBIT.interactionDeadLetterBinding());
    }
    static List<DomainEvent> create(String scenario,int n,boolean rollback){
        List<DomainEvent> events=new ArrayList<>();DomainEventOutboxServiceImpl out=context.getBean(DomainEventOutboxServiceImpl.class);
        for(int i=0;i<n;i++){
            DomainEvent e=out.newEvent(DomainEventType.PRAISE,1000L+i,DomainEvent.Target.builder().type("ARTICLE").id(2000L+i).ownerId(9000L).build(),scenario);
            e.setEventId(RUN+":"+scenario+":"+i);e.setTraceId("bench-"+scenario+"-"+i);events.add(e);FIXTURES.add(e);
        }
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(s->{
            for(DomainEvent e:events){db.update("INSERT INTO bench_source VALUES(?,?,?,?)",e.getEventId(),scenario,e.getOccurredAt().getTime(),"synthetic business record");out.append(e);}
            if(rollback)s.setRollbackOnly();return null;
        });return events;
    }
    static void handle(DomainEvent event,boolean redelivered,String deliveryKey){
        String id=UUID.randomUUID().toString();AtomicBoolean entered=new AtomicBoolean();
        db.update("INSERT INTO bench_attempt(attempt_id,event_id,scenario,delivery_key,redelivered,started_ms) VALUES(?,?,?,?,?,?)",id,event.getEventId(),event.getContent(),deliveryKey,redelivered,System.currentTimeMillis());
        try{
            context.getBean(DomainEventConsumeService.class).consume(CONSUMER,event,()->{
                entered.set(true);db.update("INSERT INTO bench_effect(event_id,scenario) VALUES(?,?)",event.getEventId(),event.getContent());
                if("dlq".equals(event.getContent())&&POISON.get())throw new IllegalStateException("injected failure after effect INSERT before transaction commit");
            });
            db.update("UPDATE bench_attempt SET finished_ms=?,handler_entered=?,outcome='COMMITTED' WHERE attempt_id=?",System.currentTimeMillis(),entered.get(),id);
        }catch(RuntimeException e){
            db.update("UPDATE bench_attempt SET finished_ms=?,handler_entered=?,outcome='FAILED',error=? WHERE attempt_id=?",System.currentTimeMillis(),entered.get(),e.getClass().getSimpleName(),id);throw e;
        }
    }
    static void startListener(){
        if(listener==null){
            SimpleRabbitListenerEndpoint endpoint=new SimpleRabbitListenerEndpoint();endpoint.setId("bench-reliability");endpoint.setQueueNames(RabbitMqAutoConfig.QUEUE);
            endpoint.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
                String key=process+":"+System.identityHashCode(channel.getConnection())+":"+channel.getChannelNumber()+":"+message.getMessageProperties().getDeliveryTag();
                handle(JsonUtil.toObj(new String(message.getBody(),StandardCharsets.UTF_8),DomainEvent.class),Boolean.TRUE.equals(message.getMessageProperties().getRedelivered()),key);
            });
            listener=RABBIT.listenerContainerFactory(context.getBean(CachingConnectionFactory.class),JSON,context.getBean(DomainEventMessageMonitor.class),new RabbitMdcTraceAdvice()).createListenerContainer(endpoint);
            listener.setConcurrentConsumers(4);listener.setPrefetchCount(1);listener.setRecoveryInterval(500);listener.setShutdownTimeout(10000);
        }listener.start();
    }
    static int effects(String scenario){return count("SELECT COUNT(DISTINCT event_id) FROM bench_effect WHERE scenario=?",scenario);}
    static void publishAll()throws Exception{
        await("publisher compensation",()->{context.getBean(DomainEventOutboxPublisher.class).publishPending();return count("SELECT COUNT(*) FROM domain_event_outbox WHERE status<>'SENT'")==0;},90);
    }
    static void finish(String scenario,int n)throws Exception{await(scenario+" business commit",()->effects(scenario)==n,90);await("queue drained",()->queueSafe(RabbitMqAutoConfig.QUEUE)==0,30);}
    static long queueSafe(String name){try{Channel c=context.getBean(CachingConnectionFactory.class).createConnection().createChannel(false);try{return c.queueDeclarePassive(name).getMessageCount();}finally{c.close();}}catch(Exception e){return -1;}}
    static void checkpoint(String name)throws Exception{
        Map<String,Object> state=map("at",System.currentTimeMillis(),"outboxEvents",count("SELECT COUNT(*) FROM domain_event_outbox"),"businessSourceRows",count("SELECT COUNT(*) FROM bench_source"),
            "successEvents",count("SELECT COUNT(DISTINCT event_id) FROM bench_effect"),"businessWrites",count("SELECT COUNT(*) FROM bench_effect"),"consumeRows",count("SELECT COUNT(*) FROM domain_event_consume"),
            "outboxStates",db.queryForList("SELECT status,COUNT(*) AS count,SUM(retry_count) AS retry_count FROM domain_event_outbox GROUP BY status"),
            "dlqReady",queueSafe(RabbitMqAutoConfig.DLQ),"queueReady",queueSafe(RabbitMqAutoConfig.QUEUE));
        save(name+".json",state);
    }
    static void send(DomainEvent e)throws Exception{
        CorrelationData c=new CorrelationData(UUID.randomUUID().toString());context.getBean(RabbitTemplate.class).convertAndSend(RabbitMqAutoConfig.EXCHANGE,RabbitMqAutoConfig.ROUTING_KEY,e,c);
        if(!c.getFuture().get(5,TimeUnit.SECONDS).isAck()||c.getReturnedMessage()!=null)throw new AssertionError("Replay/duplicate publish not confirmed");
    }
    static void compose(String action)throws Exception{
        if(!RUN.matches("bench-ob-[0-9]+"))throw new IllegalStateException("Unsafe project");
        long start=System.currentTimeMillis();
        Process p=new ProcessBuilder("docker","compose","-p",RUN,"-f","benchmarks/config/outbox-compose.yml",action,"rabbit").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(DIR.resolve("rabbit-fault.log").toFile())).start();
        if(!p.waitFor(60,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IllegalStateException("Dedicated Rabbit "+action+" failed");
        log("fault-timeline.jsonl",map("action",action,"startedMs",start,"finishedMs",System.currentTimeMillis()));
    }
    static void crashChild()throws Exception{
        Process p=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-Dlogback.configurationFile=benchmarks/config/es-logback.xml","-cp",System.getProperty("java.class.path"),"OutboxReliabilityHarness","crash-child").redirectErrorStream(true).redirectOutput(DIR.resolve("ack-child.log").toFile()).start();
        if(!p.waitFor(60,TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("Crash child timed out");}
        check("real process death after commit before ACK",p.exitValue()==77,p.exitValue());
    }
    static void child()throws Exception{
        process="crash-child";check("child owner guard",RUN.equals(db.queryForObject("SELECT run_id FROM bench_guard",String.class)),RUN);
        Channel channel=context.getBean(CachingConnectionFactory.class).createConnection().createChannel(false);
        for(int i=0;i<8;i++){
            GetResponse r=channel.basicGet(RabbitMqAutoConfig.QUEUE,false);if(r==null)throw new AssertionError("Missing crash message");
            DomainEvent e=JsonUtil.toObj(new String(r.getBody(),StandardCharsets.UTF_8),DomainEvent.class);
            if(!"ack_crash".equals(e.getContent()))throw new AssertionError("Unexpected crash input");
            handle(e,r.getEnvelope().isRedeliver(),process+":"+r.getEnvelope().getDeliveryTag());
        }
        System.out.println("COMMITTED_8_EVENTS_WITHOUT_ACK");System.out.flush();Runtime.getRuntime().halt(77);
    }
    static void matrix()throws Exception{
        init();startListener();
        create("normal",32,false);publishAll();finish("normal",32);checkpoint("01-normal");check("normal effects",effects("normal")==32,32);
        create("rollback",8,true);check("business transaction rollback",count("SELECT COUNT(*) FROM bench_source WHERE scenario='rollback'")==0,0);
        check("outbox transaction rollback",count("SELECT COUNT(*) FROM domain_event_outbox WHERE event_id LIKE ?",RUN+":rollback:%")==0,0);checkpoint("02-rollback");
        create("send_failure",8,false);RabbitAdmin admin=context.getBean(RabbitAdmin.class);admin.removeBinding(RABBIT.interactionBinding());
        context.getBean(DomainEventOutboxPublisher.class).publishPending();checkpoint("03-send-failure");
        check("committed DB + mandatory return retained FAILED",count("SELECT COUNT(*) FROM domain_event_outbox WHERE status='FAILED'")==8&&effects("send_failure")==0,8);
        recoveryStart=System.currentTimeMillis();admin.declareBinding(RABBIT.interactionBinding());publishAll();finish("send_failure",8);
        log("recoveries.jsonl",map("scenario","send_failure","mode","production publishPending; natural retry due time","startMs",recoveryStart,"finishMs",System.currentTimeMillis()));
        listener.stop();create("rabbit_outage",8,false);compose("stop");context.getBean(CachingConnectionFactory.class).resetConnection();
        context.getBean(DomainEventOutboxPublisher.class).publishPending();checkpoint("04-rabbit-unavailable");
        check("broker outage retained FAILED",count("SELECT COUNT(*) FROM domain_event_outbox WHERE status='FAILED'")==8,8);
        recoveryStart=System.currentTimeMillis();compose("start");context.getBean(CachingConnectionFactory.class).resetConnection();await("broker ready",OutboxReliabilityHarness::ready,90);startListener();publishAll();finish("rabbit_outage",8);
        log("recoveries.jsonl",map("scenario","rabbit_outage","mode","container restart + production publisher compensation","startMs",recoveryStart,"finishMs",System.currentTimeMillis()));
        listener.stop();create("ack_crash",8,false);publishAll();crashChild();
        check("crash child committed business before ACK",effects("ack_crash")==8,8);recoveryStart=System.currentTimeMillis();startListener();
        await("broker redelivery after crash",()->count("SELECT COUNT(DISTINCT event_id) FROM bench_attempt WHERE scenario='ack_crash' AND redelivered=1")==8,30);
        await("redelivery deduplicated",()->count("SELECT COUNT(*) FROM bench_attempt WHERE scenario='ack_crash' AND outcome='COMMITTED'")==16,30);
        log("recoveries.jsonl",map("scenario","ack_crash","mode","new listener receives broker redeliveries","startMs",recoveryStart,"finishMs",System.currentTimeMillis()));checkpoint("05-ack-crash");
        List<DomainEvent> duplicates=create("duplicate",32,false);publishAll();for(int i=0;i<2;i++)for(DomainEvent e:duplicates)send(e);
        await("96 duplicate deliveries",()->count("SELECT COUNT(*) FROM bench_attempt WHERE scenario='duplicate' AND outcome='COMMITTED'")==96,60);
        check("duplicates cause only 32 writes",count("SELECT COUNT(*) FROM bench_effect WHERE scenario='duplicate'")==32,32);checkpoint("06-duplicate");
        create("dlq",8,false);publishAll();await("actual broker DLQ arrivals",()->queueSafe(RabbitMqAutoConfig.DLQ)==8,60);listener.stop();
        check("failed handlers rolled back effects and consume tokens",effects("dlq")==0&&count("SELECT COUNT(*) FROM domain_event_consume WHERE event_id LIKE ?",RUN+":dlq:%")==0,0);
        check("production retry attempts = 3 per poison event",count("SELECT COUNT(*) FROM bench_attempt WHERE scenario='dlq' AND outcome='FAILED'")==24,24);checkpoint("07-before-manual-compensation");
        // Production has no generic DLQ replay task. Explicit benchmark admin action; never count dead-lettered as business success.
        POISON.set(false);recoveryStart=System.currentTimeMillis();startListener();
        Channel channel=context.getBean(CachingConnectionFactory.class).createConnection().createChannel(false);
        try{for(int i=0;i<8;i++){
            GetResponse dead=channel.basicGet(RabbitMqAutoConfig.DLQ,false);if(dead==null)throw new AssertionError("Missing DLQ event");
            DomainEvent e=JsonUtil.toObj(new String(dead.getBody(),StandardCharsets.UTF_8),DomainEvent.class);
            log("dlq-messages.jsonl",map("eventId",e.getEventId(),"xDeath",String.valueOf(dead.getProps().getHeaders().get("x-death")),"body",new String(dead.getBody(),StandardCharsets.UTF_8)));
            send(e);channel.basicAck(dead.getEnvelope().getDeliveryTag(),false);
        }}finally{channel.close();}
        finish("dlq",8);log("recoveries.jsonl",map("scenario","dlq","mode","explicit benchmark DLQ replay; NOT existing automatic compensation","startMs",recoveryStart,"finishMs",System.currentTimeMillis()));
        listener.stop();checkpoint("08-final");
        check("96 committed events have one business write each",count("SELECT COUNT(*) FROM bench_effect")==96&&count("SELECT COUNT(DISTINCT event_id) FROM bench_effect")==96,db.queryForList("SELECT scenario,COUNT(*) AS writes FROM bench_effect GROUP BY scenario"));
        check("all consume tokens and publisher states complete",count("SELECT COUNT(*) FROM domain_event_consume")==96&&count("SELECT COUNT(*) FROM domain_event_outbox WHERE status='SENT'")==96,96);
        check("final DLQ drained by explicit compensation",queueSafe(RabbitMqAutoConfig.DLQ)==0,0);
    }
    public static void main(String[] args)throws Exception{
        if(RUN==null||!RUN.matches("bench-ob-[0-9]+")||System.getenv("BENCH_TEST_PASSWORD")==null)throw new IllegalStateException("Isolated runner required");
        context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);
        if(args.length>0){child();return;}
        boolean pass=false,exportOk=true;long start=System.currentTimeMillis();Throwable failure=null;
        try{matrix();pass=true;}catch(Throwable e){failure=e;log("failures.jsonl",map("type",e.getClass().getName(),"message",String.valueOf(e.getMessage())));e.printStackTrace();}
        finally{
            if(listener!=null)listener.stop();
            save("checks.json",CHECKS);save("fixtures.json",FIXTURES);
            for(String table:Arrays.asList("domain_event_outbox","domain_event_consume","bench_source","bench_effect","bench_attempt")){
                try{
                    List<Map<String,Object>> rows=db.queryForList("SELECT * FROM "+table);
                    for(Map<String,Object> row:rows)row.replaceAll((k,v)->v instanceof java.time.temporal.TemporalAccessor?v.toString():v);
                    save(table+".json",rows);
                }catch(Exception e){exportOk=false;log("snapshot-errors.jsonl",map("table",table,"error",e.getClass().getName()));}
            }
            List<Map<String,Object>> meters=new ArrayList<>();context.getBean(SimpleMeterRegistry.class).getMeters().forEach(m->m.measure().forEach(v->meters.add(map("name",m.getId().getName(),"tags",m.getId().getTags().toString(),"statistic",v.getStatistic().toString(),"value",v.getValue()))));save("meters.json",meters);
            save("harness-status.json",map("pass",pass&&exportOk,"executionPass",pass,"rawSnapshotsComplete",exportOk,"startedMs",start,"finishedMs",System.currentTimeMillis(),"failure",failure==null?null:failure.getClass().getName()));
            context.close();
        }
        if(!pass||!exportOk)System.exit(1);
    }
}

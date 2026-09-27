import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.web.front.chat.stomp.WsChatConfig;
import com.github.paicoding.forum.web.front.chat.stomp.WsChatProperties;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Saturates the actual inbound executor deterministically without sockets or external services. */
public class ChatInboundBackpressureCheck {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        WsChatConfig config = new WsChatConfig();
        WsChatProperties properties = new WsChatProperties();
        ReflectionTestUtils.setField(config, "properties", properties);
        ChannelRegistration registration = new ChannelRegistration();
        config.configureClientInboundChannel(registration);
        ThreadPoolTaskExecutor executor = ReflectionTestUtils.invokeMethod(registration.taskExecutor(), "getTaskExecutor");
        executor.initialize();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workers = new CountDownLatch(pool.getMaximumPoolSize());
        AtomicInteger queuedExecuted = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        List<String> handlerThreads = new CopyOnWriteArrayList<>();
        String caller = Thread.currentThread().getName();
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel(executor);
        for (int i = 0; i < 3; i++) {
            final int handler = i;
            channel.subscribe(message -> {
                handlerCalls.incrementAndGet();
                handlerThreads.add(handler + ":" + Thread.currentThread().getName());
            });
        }
        Runnable blocker = () -> {
            workers.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        int capacity = properties.getOutboundQueueCapacity();
        try {
            for (int i = 0; i < pool.getCorePoolSize(); i++) executor.execute(blocker);
            for (int i = 0; i < capacity; i++) executor.execute(queuedExecuted::incrementAndGet);
            for (int i = pool.getCorePoolSize(); i < pool.getMaximumPoolSize(); i++) executor.execute(blocker);
            if (!workers.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Could not saturate all workers");
            result.put("corePoolSize", pool.getCorePoolSize());
            result.put("maxPoolSize", pool.getMaximumPoolSize());
            result.put("queueCapacity", capacity);
            result.put("queueDepthAtSend", pool.getQueue().size());
            boolean rejected = false;
            try { channel.send(new GenericMessage<>("saturated-inbound-frame")); }
            catch (RuntimeException e) { rejected = true; result.put("sendError", e.toString()); }
            result.put("saturatedSendRejected", rejected);
            result.put("handlerCalls", handlerCalls.get());
            result.put("handlerThreads", handlerThreads);
            result.put("callerThread", caller);
            if (rejected) failures.add("Saturated inbound frame was rejected");
            if (handlerCalls.get() != 3) failures.add("Each subscribed handler must receive the frame exactly once");
            if (handlerThreads.stream().anyMatch(t -> !t.endsWith(":" + caller))) failures.add("Saturation must apply caller-thread backpressure");
            if (pool.getQueue().size() != capacity) failures.add("Queue capacity changed during saturated send");
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                failures.add("Executor did not terminate");
            }
        }
        result.put("queuedTasksExecuted", queuedExecuted.get());
        if (queuedExecuted.get() != capacity) failures.add("Accepted queued tasks did not execute exactly once");
        boolean shutdownRejected = false;
        try { channel.send(new GenericMessage<>("after-shutdown")); }
        catch (RuntimeException expected) { shutdownRejected = true; }
        result.put("shutdownRejected", shutdownRejected);
        if (!shutdownRejected) failures.add("Shutdown executor silently discarded a frame");
        if (handlerCalls.get() > 3) failures.add("Shutdown executor delivered an unexpected frame");
        result.put("failures", failures);
        result.put("passed", failures.isEmpty());
        result.put("durationMs", (System.nanoTime() - started) / 1e6);
        result.put("realModelCalls", 0);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Paths.get(args[0], "checks.json").toFile(), result);
        System.out.println(new ObjectMapper().writeValueAsString(result));
        System.exit(failures.isEmpty() ? 0 : 2);
    }
}

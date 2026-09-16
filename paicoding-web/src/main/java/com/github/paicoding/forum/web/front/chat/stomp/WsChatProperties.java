package com.github.paicoding.forum.web.front.chat.stomp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chat.websocket")
public class WsChatProperties {
    private String allowedOriginPatterns = "";
    private int maxConnectionsPerUser = 3;
    private long authMaxAgeSeconds = 1800;
    private int sendTimeLimitMs = 10000;
    private int sendBufferBytes = 524288;
    private int messageSizeBytes = 65536;
    private int outboundQueueCapacity = 256;
    private int replayMaxEvents = 200;
    private int generationBufferBytes = 524288;
    private long replayTtlSeconds = 900;
    private long modelTimeoutMs = 120000;
    private BrokerRelay brokerRelay = new BrokerRelay();

    @Data
    public static class BrokerRelay {
        private boolean enabled;
        private String host = "127.0.0.1";
        private int port = 61613;
        private String clientLogin = "guest";
        private String clientPasscode = "guest";
        private String systemLogin = "guest";
        private String systemPasscode = "guest";
    }
}

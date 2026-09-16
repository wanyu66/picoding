package com.github.paicoding.forum.web.front.chat.stomp;

import com.github.paicoding.forum.web.global.GlobalInitService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableWebSocketMessageBroker
public class WsChatConfig implements WebSocketMessageBrokerConfigurer {
    @Autowired private WsChatProperties properties;
    @Autowired private WsConnectionRegistry connections;
    @Autowired private GlobalInitService globalInitService;

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        if (properties.getBrokerRelay().isEnabled()) {
            WsChatProperties.BrokerRelay relay = properties.getBrokerRelay();
            StompBrokerRelayRegistration relayRegistration = config.enableStompBrokerRelay("/chat", "/msg", "/topic", "/queue")
                    .setRelayHost(relay.getHost()).setRelayPort(relay.getPort())
                    .setClientLogin(relay.getClientLogin()).setClientPasscode(relay.getClientPasscode())
                    .setSystemLogin(relay.getSystemLogin()).setSystemPasscode(relay.getSystemPasscode());
            relayRegistration.setUserDestinationBroadcast("/topic/unresolved-user")
                    .setUserRegistryBroadcast("/topic/simp-user-registry");
        } else {
            config.enableSimpleBroker("/chat", "/msg")
                    .setHeartbeatValue(new long[]{10000L, 20000L})
                    .setTaskScheduler(wsHeartbeatScheduler());
        }
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/gpt/{id}/{aiType}", "/notify")
                .setHandshakeHandler(new AuthHandshakeHandler())
                .addInterceptors(new AuthHandshakeInterceptor());
        // 不配置时使用 Spring 的同源策略；生产环境通过配置显式增加可信 Origin，禁止通配符。
        if (StringUtils.isNotBlank(properties.getAllowedOriginPatterns())) {
            endpoint.setAllowedOriginPatterns(StringUtils.split(properties.getAllowedOriginPatterns(), ','));
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(10)
                .queueCapacity(properties.getOutboundQueueCapacity()).keepAliveSeconds(60);
        registration.interceptors(channelInInterceptor());
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(10)
                .queueCapacity(properties.getOutboundQueueCapacity()).keepAliveSeconds(60);
        registration.interceptors(channelOutInterceptor());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(properties.getSendTimeLimitMs())
                .setSendBufferSizeLimit(properties.getSendBufferBytes())
                .setMessageSizeLimit(properties.getMessageSizeBytes());
    }

    @Bean public HandshakeHandler handshakeHandler() { return new AuthHandshakeHandler(); }
    @Bean public HttpSessionHandshakeInterceptor handshakeInterceptor() { return new AuthHandshakeInterceptor(); }
    @Bean public ChannelInterceptor channelInInterceptor() { return new AuthInChannelInterceptor(connections, globalInitService, properties); }
    @Bean public ChannelInterceptor channelOutInterceptor() { return new AuthOutChannelInterceptor(); }
    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}

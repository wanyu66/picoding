package com.github.paicoding.forum.web.config;

import com.github.paicoding.forum.service.statistics.service.StatisticsSettingService;
import com.github.paicoding.forum.web.global.GlobalInitService;
import com.github.paicoding.forum.web.hook.filter.ReqRecordFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the request filter as a Spring-managed bean so its dependencies are
 * supplied by the application context rather than by the servlet container.
 */
@Configuration
public class ReqRecordFilterConfig {

    @Bean
    public FilterRegistrationBean<ReqRecordFilter> reqRecordFilterRegistration(
            GlobalInitService globalInitService,
            StatisticsSettingService statisticsSettingService) {
        ReqRecordFilter filter = new ReqRecordFilter(globalInitService, statisticsSettingService);
        FilterRegistrationBean<ReqRecordFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setName("reqRecordFilter");
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        return registration;
    }
}

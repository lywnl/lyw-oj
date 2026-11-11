package com.lyw.system.questionsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.reconcile.QuestionReconcileProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(QuestionReconcileProperties.class)
public class QuestionReconcileWebConfiguration {
    @Bean public FilterRegistrationBean<QuestionReconcileAuthFilter> reconcileAuth(
            QuestionReconcileProperties properties, ObjectMapper mapper) {
        properties.validateServer();
        var filter = new FilterRegistrationBean<>(new QuestionReconcileAuthFilter(properties, mapper));
        filter.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        filter.addUrlPatterns("/*");
        return filter;
    }
}

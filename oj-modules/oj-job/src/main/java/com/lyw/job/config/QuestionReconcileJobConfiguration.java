package com.lyw.job.config;

import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.api.reconcile.RemoteQuestionReconcileService;
import com.lyw.api.reconcile.ValidatedReconcileClientCondition;
import com.lyw.job.handler.QuestionIndexReconcileJob;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Conditional(ValidatedReconcileClientCondition.class)
@EnableConfigurationProperties(QuestionReconcileProperties.class)
@EnableFeignClients(clients = RemoteQuestionReconcileService.class)
public class QuestionReconcileJobConfiguration {
    public QuestionReconcileJobConfiguration(QuestionReconcileProperties properties, Environment environment) {
        properties.validateClient();
        if (properties.getInternalToken().equals(environment.getProperty("xxl.job.accessToken")))
            throw new IllegalStateException("内部对账令牌必须独立于 XXL-JOB 执行器令牌");
    }

    @Bean public QuestionIndexReconcileJob questionIndexReconcileJob(RemoteQuestionReconcileService remote,
            QuestionReconcileProperties properties) {
        return new QuestionIndexReconcileJob(remote, properties);
    }
}

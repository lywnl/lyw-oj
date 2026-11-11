package com.lyw.api.reconcile;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 先脱敏校验，再允许 Feign Registrar 解析 URL，防止启动异常回显 URL 内误填的凭据。 */
public final class ValidatedReconcileClientCondition extends SpringBootCondition {
    @Override public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        if (!"true".equalsIgnoreCase(environment.getProperty("question.reconcile.enabled")))
            return ConditionOutcome.noMatch("题目对账客户端未启用");
        QuestionReconcileProperties properties;
        try {
            properties = Binder.get(environment).bind("question.reconcile", QuestionReconcileProperties.class).get();
        } catch (RuntimeException invalidBinding) {
            throw new IllegalStateException("question.reconcile 配置绑定失败，请检查配置类型");
        }
        properties.validateClient();
        if (properties.getInternalToken().equals(environment.getProperty("xxl.job.accessToken")))
            throw new IllegalStateException("内部对账令牌必须独立于 XXL-JOB 执行器令牌");
        return ConditionOutcome.match("题目对账客户端启用配置已校验");
    }
}

package com.lyw.friend.rabbit;

import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.dto.JudgeSubmissionIdentity;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.redis.service.StateCacheService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class JudgeProducer {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private StateCacheService stateCacheService;

    private static final int MAX_RETRY = 3;
    private final ConcurrentHashMap<String, JudgeSubmitDTO> pendingMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        rabbitTemplate.setMandatory(true);
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            factory.setPublisherReturns(true);
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        }
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) return;
            String msgId = correlationData.getId();
            if (ack) {
                pendingMessages.remove(msgId);
                retryCount.remove(msgId);
            } else {
                log.warn("消息确认失败，messageId={}，原因={}", msgId, cause);
                retryMessage(msgId);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            String msgId = returned.getMessage().getMessageProperties().getMessageId();
            JudgeSubmitDTO submission = msgId == null ? null : pendingMessages.remove(msgId);
            if (msgId != null) retryCount.remove(msgId);
            // Returns 与 Confirm 可能交错，原始业务身份仍在消息正文中。
            if (submission == null) {
                try {
                    submission = JSON.parseObject(returned.getMessage().getBody(), JudgeSubmitDTO.class);
                } catch (Exception e) {
                    log.error("无法识别退回消息，messageId={}", msgId);
                    return;
                }
            }
            log.warn("消息路由失败，messageId={}，submitId={}，原因={}", msgId,
                    submission == null ? null : submission.getSubmitId(), returned.getReplyText());
            markJudgeFailed(submission);
        });
    }

    public void produceMsg(JudgeSubmitDTO submission) {
        if (!JudgeSubmissionIdentity.isValid(submission)) throw new IllegalArgumentException("发送消息缺少有效提交身份");
        String msgId = UUID.randomUUID().toString();
        pendingMessages.put(msgId, submission);
        retryCount.put(msgId, 0);
        try {
            send(msgId, submission);
        } catch (Exception e) {
            pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            markJudgeFailed(submission);
            throw new IllegalStateException(ResultCode.FAILED_RABBIT_PRODUCE.getMsg(), e);
        }
    }

    private void send(String msgId, JudgeSubmitDTO submission) {
        rabbitTemplate.convertAndSend(RabbitMQConstants.OJ_EXCHANGE, RabbitMQConstants.OJ_ROUTING_KEY,
                submission, message -> {
                    message.getMessageProperties().setMessageId(msgId);
                    message.getMessageProperties().setHeader("submitId", submission.getSubmitId().toString());
                    return message;
                }, new CorrelationData(msgId));
    }

    private void retryMessage(String msgId) {
        JudgeSubmitDTO submission = pendingMessages.get(msgId);
        if (submission == null) return;
        int count = retryCount.merge(msgId, 1, Integer::sum);
        if (count > MAX_RETRY) {
            pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            markJudgeFailed(submission);
            return;
        }
        try {
            log.info("消息重发，submitId={}，messageId={}，次数={}", submission.getSubmitId(), msgId, count);
            send(msgId, submission);
        } catch (Exception e) {
            pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            markJudgeFailed(submission);
        }
    }

    private void markJudgeFailed(JudgeSubmitDTO submission) {
        if (!JudgeSubmissionIdentity.isValid(submission)) {
            log.error("发送失败消息缺少提交身份，不能写结果缓存");
            return;
        }
        try {
            stateCacheService.update(CacheConstants.getJudgeResultKey(submission.getUserId(), submission.getSubmitId()),
                    JudgeResultCache.error(submission.getUserId(), submission.getSubmitId(), "判题服务暂时不可用，请继续查询结果"),
                    CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("发送失败提示未写入缓存，submitId={}，原因={}", submission.getSubmitId(), e.getMessage());
        }
    }
}

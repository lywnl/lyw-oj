package com.lyw.judge.rabbit;

import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.dto.JudgeSubmissionIdentity;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.redis.service.StateCacheService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DeadLetterConsumer {
    @Autowired
    private StateCacheService stateCacheService;
    @Autowired
    private DeadLetterListenerLifecycle lifecycle;

    @RabbitListener(id = DeadLetterListenerLifecycle.LISTENER_ID, queues = RabbitMQConstants.OJ_DLX_QUEUE,
            containerFactory = "deadLetterContainerFactory", ackMode = "MANUAL")
    public void consumeDeadLetter(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();
        JudgeSubmitDTO submission;
        try {
            submission = JSON.parseObject(message.getBody(), JudgeSubmitDTO.class);
            if (!JudgeSubmissionIdentity.isValid(submission)) throw new IllegalArgumentException("非法提交身份");
        } catch (Exception e) {
            log.error("非法死信保留待人工处理，queue={}，messageId={}，deliveryTag={}",
                    RabbitMQConstants.OJ_DLX_QUEUE, messageId, tag);
            pause(channel);
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                stateCacheService.update(CacheConstants.getJudgeResultKey(submission.getUserId(), submission.getSubmitId()),
                        JudgeResultCache.error(submission.getUserId(), submission.getSubmitId(), "系统判题异常，请继续查询结果"),
                        CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.error("死信缓存更新失败，queue={}，submitId={}，messageId={}，次数={}，原因={}",
                        RabbitMQConstants.OJ_DLX_QUEUE, submission.getSubmitId(), messageId, attempt, e.getMessage());
                if (attempt == 3 || Thread.currentThread().isInterrupted()) break;
                try {
                    Thread.sleep(attempt * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            // 被已有真实/同级结果保护也算完成，不要求发生一次实际覆盖。
            MessageAcknowledgement.ack(channel, tag);
            return;
        }
        pause(channel);
    }

    private void pause(Channel channel) {
        try {
            lifecycle.pause();
        } catch (RuntimeException shutdownFailure) {
            log.error("无法调度死信监听暂停，关闭通道保留未确认消息");
            MessageAcknowledgement.abort(channel);
        }
    }
}

package com.lyw.judge.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.dto.JudgeSubmissionIdentity;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.JudgeSystemException;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JudgeConsumer {
    @Autowired
    private IJudgeService judgeService;
    private static final int MAX_ATTEMPTS = 3;

    @RabbitListener(queues = RabbitMQConstants.OJ_WORK_QUEUE, ackMode = "MANUAL",
            concurrency = "${sandbox.docker.pool.consumer-concurrency:8}")
    public void consumeMsg(JudgeSubmitDTO submission, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        if (!JudgeSubmissionIdentity.isValid(submission)) {
            log.error("非法判题消息进入死信隔离，deliveryTag={}", deliveryTag);
            MessageAcknowledgement.reject(channel, deliveryTag);
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                UserQuestionResultVO result = judgeService.doJudgeJavaCode(submission);
                if (result == null || result.getResultState() != JudgeResultState.JUDGED) {
                    throw new IllegalStateException("判题服务未返回已持久化结果");
                }
            } catch (Exception failure) {
                if (failure instanceof JudgeSystemException system && system.getStatus() == CodeRunStatus.SYSTEM_BUSY) {
                    MessageAcknowledgement.reject(channel, deliveryTag);
                    return;
                }
                log.warn("判题尝试失败，submitId={}，deliveryTag={}，次数={}，原因={}",
                        submission.getSubmitId(), deliveryTag, attempt, failure.getMessage());
                if (attempt == MAX_ATTEMPTS || Thread.currentThread().isInterrupted()) break;
                try {
                    Thread.sleep(1000L * (1L << (attempt - 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            // 与业务 try/catch 分离：ACK 失败不能触发再次判题。
            MessageAcknowledgement.ack(channel, deliveryTag);
            return;
        }
        MessageAcknowledgement.reject(channel, deliveryTag);
    }
}

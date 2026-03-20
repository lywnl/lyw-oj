package com.lyw.judge.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.judge.service.IJudgeService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class JudgeConsumer {

    @Autowired
    private IJudgeService judgeService;

    private static final int MAX_RETRY = 3;

    @RabbitListener(queues = RabbitMQConstants.OJ_WORK_QUEUE, ackMode = "MANUAL")
    public void consumeMsg(JudgeSubmitDTO judgeSubmitDTO, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("rabbitmq接收消息：{}", judgeSubmitDTO);
        for (int i = 1; i <= MAX_RETRY; i++) {
            try {
                judgeService.doJudgeJavaCode(judgeSubmitDTO);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (Exception e) {
                log.warn("判题消费失败，第{}次重试，原因：{}", i, e.getMessage());
                if (i < MAX_RETRY) {
                    try {
                        // 指数退避：1秒、2秒、4秒
                        Thread.sleep(1000L * (1L << (i - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // 重试耗尽，拒绝消息，不重回队列，进入死信队列
        log.error("判题消费失败，已重试{}次，消息进入死信队列，userId={}，questionId={}",
                MAX_RETRY, judgeSubmitDTO.getUserId(), judgeSubmitDTO.getQuestionId());
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("消息nack失败：{}", e.getMessage());
        }
    }
}

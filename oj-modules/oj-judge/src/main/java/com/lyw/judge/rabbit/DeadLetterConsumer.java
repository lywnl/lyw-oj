package com.lyw.judge.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.redis.service.RedisService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DeadLetterConsumer {

    @Autowired
    private RedisService redisService;

    @RabbitListener(queues = RabbitMQConstants.OJ_DLX_QUEUE, ackMode = "MANUAL")
    public void consumeDeadLetter(JudgeSubmitDTO judgeSubmitDTO, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.error("死信队列收到消息，判题失败：userId={}，questionId={}，examId={}",
                judgeSubmitDTO.getUserId(), judgeSubmitDTO.getQuestionId(), judgeSubmitDTO.getExamId());
        try {
            // 更新Redis缓存为失败状态，让前端轮询感知到判题失败
            String key = CacheConstants.getJudgeResultKey(
                    judgeSubmitDTO.getUserId(), judgeSubmitDTO.getQuestionId(), judgeSubmitDTO.getExamId());
            UserQuestionResultVO failResult = new UserQuestionResultVO();
            failResult.setPass(Constants.FALSE);
            failResult.setExeMessage("系统判题异常，请稍后重试");
            redisService.setCacheObject(key, failResult, CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("死信队列消费异常：{}", e.getMessage());
            try {
                // 死信消费失败也ACK，避免消息堆积（已记录日志）
                channel.basicAck(deliveryTag, false);
            } catch (Exception ex) {
                log.error("死信队列ACK失败：{}", ex.getMessage());
            }
        }
    }
}

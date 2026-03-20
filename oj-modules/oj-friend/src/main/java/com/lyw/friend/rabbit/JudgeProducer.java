package com.lyw.friend.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.redis.service.RedisService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
    private RedisService redisService;

    private static final int MAX_RETRY = 3;

    // 记录待确认的消息，用于Confirm失败时重发
    private final ConcurrentHashMap<String, JudgeSubmitDTO> pendingMessages = new ConcurrentHashMap<>();
    // 记录每条消息的重试次数
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 消息到达交换机的确认回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            String msgId = correlationData.getId();
            if (ack) {
                // 确认成功，清理缓存
                pendingMessages.remove(msgId);
                retryCount.remove(msgId);
            } else {
                // 确认失败，尝试重发
                log.error("消息Confirm失败，msgId={}，原因：{}", msgId, cause);
                retryMessage(msgId);
            }
        });
        // 消息从交换机路由到队列失败的回调
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("消息路由到队列失败，交换机：{}，路由键：{}，原因：{}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
    }

    public void produceMsg(JudgeSubmitDTO judgeSubmitDTO) {
        String msgId = UUID.randomUUID().toString();
        pendingMessages.put(msgId, judgeSubmitDTO);
        retryCount.put(msgId, 0);
        try {
            CorrelationData correlationData = new CorrelationData(msgId);
            rabbitTemplate.convertAndSend(
                    RabbitMQConstants.OJ_EXCHANGE,
                    RabbitMQConstants.OJ_ROUTING_KEY,
                    judgeSubmitDTO,
                    correlationData);
        } catch (Exception e) {
            pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            log.error("rabbitmq发送消息失败：{}", e.getMessage());
            throw new RuntimeException(ResultCode.FAILED_RABBIT_PRODUCE.getMsg());
        }
    }

    private void retryMessage(String msgId) {
        int count = retryCount.getOrDefault(msgId, 0) + 1;
        if (count > MAX_RETRY) {
            log.error("消息重发已达最大次数{}，放弃发送，msgId={}", MAX_RETRY, msgId);
            // 重试耗尽，更新Redis为失败状态，让前端感知
            JudgeSubmitDTO dto = pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            if (dto != null) {
                markJudgeFailed(dto);
            }
            return;
        }
        retryCount.put(msgId, count);
        JudgeSubmitDTO dto = pendingMessages.get(msgId);
        if (dto == null) {
            return;
        }
        log.warn("消息第{}次重发，msgId={}", count, msgId);
        try {
            CorrelationData correlationData = new CorrelationData(msgId);
            rabbitTemplate.convertAndSend(
                    RabbitMQConstants.OJ_EXCHANGE,
                    RabbitMQConstants.OJ_ROUTING_KEY,
                    dto,
                    correlationData);
        } catch (Exception e) {
            log.error("消息重发异常，msgId={}，原因：{}", msgId, e.getMessage());
            pendingMessages.remove(msgId);
            retryCount.remove(msgId);
            markJudgeFailed(dto);
        }
    }

    private void markJudgeFailed(JudgeSubmitDTO dto) {
        try {
            String key = CacheConstants.getJudgeResultKey(dto.getUserId(), dto.getQuestionId(), dto.getExamId());
            UserQuestionResultVO failResult = new UserQuestionResultVO();
            failResult.setPass(Constants.FALSE);
            failResult.setExeMessage("判题服务暂时不可用，请稍后重试");
            redisService.setCacheObject(key, failResult, CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("更新Redis判题失败状态异常：{}", e.getMessage());
        }
    }
}

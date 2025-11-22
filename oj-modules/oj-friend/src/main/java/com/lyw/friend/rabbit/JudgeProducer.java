package com.lyw.friend.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.common.core.enums.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JudgeProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void produceMsg(JudgeSubmitDTO judgeSubmitDTO) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConstants.OJ_WORK_QUEUE, judgeSubmitDTO);
        } catch (Exception e) {
            log.error("rabbitmq发送消息失败：{}", e.getMessage());
            throw new RuntimeException(ResultCode.FAILED_RABBIT_PRODUCE.getMsg());
        }
    }
}

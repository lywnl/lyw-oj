package com.lyw.judge.rabbit;

import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.common.core.constants.RabbitMQConstants;
import com.lyw.judge.service.IJudgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JudgeConsumer {

    @Autowired
    private IJudgeService judgeService;

    @RabbitListener(queues = RabbitMQConstants.OJ_WORK_QUEUE)
    public void consumeMsg(JudgeSubmitDTO judgeSubmitDTO) {
        log.info("rabbitmq接收消息：{}", judgeSubmitDTO);
        judgeService.doJudgeJavaCode(judgeSubmitDTO);
    }

}

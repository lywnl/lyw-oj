package com.lyw.friend.service;

import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.friend.rabbit.JudgeProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JudgeProducerTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final StateCacheService cache = mock(StateCacheService.class);
    private final JudgeProducer producer = new JudgeProducer();
    private RabbitTemplate.ConfirmCallback confirms;
    private RabbitTemplate.ReturnsCallback returns;
    private final List<JudgeSubmitDTO> sent = new ArrayList<>();
    private final List<CorrelationData> ids = new ArrayList<>();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(producer, "rabbitTemplate", template);
        ReflectionTestUtils.setField(producer, "stateCacheService", cache);
        doAnswer(call -> { confirms = call.getArgument(0); return null; }).when(template).setConfirmCallback(any());
        doAnswer(call -> { returns = call.getArgument(0); return null; }).when(template).setReturnsCallback(any());
        doAnswer(call -> {
            sent.add(call.getArgument(2));
            ids.add(call.getArgument(4));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        producer.init();
    }

    @Test
    void retriesKeepBusinessIdAndOriginalDto() {
        JudgeSubmitDTO submission = submission();
        producer.produceMsg(submission);
        confirms.confirm(ids.get(0), false, "连接异常");
        assertEquals(2, sent.size());
        assertSame(submission, sent.get(1));
        assertEquals(10001L, sent.get(1).getSubmitId());
        assertEquals(ids.get(0).getId(), ids.get(1).getId());
        verify(template).setMandatory(true);
    }

    @Test
    void returnedMessageStillReportsItsIdAfterConfirmMapWasCleared() {
        JudgeSubmitDTO submission = submission();
        producer.produceMsg(submission);
        confirms.confirm(ids.get(0), true, null);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(ids.get(0).getId());
        returns.returnedMessage(new ReturnedMessage(new Message(JSON.toJSONBytes(submission), properties),
                312, "NO_ROUTE", "test-exchange", "test-route"));
        ArgumentCaptor<JudgeResultCache> state = ArgumentCaptor.forClass(JudgeResultCache.class);
        verify(cache).update(eq("j:r:7:10001"), state.capture(), eq(5L), any());
        assertEquals("SYSTEM_ERROR", state.getValue().getCacheState());
        assertEquals(10001L, state.getValue().getSubmitId());
    }

    @Test
    void synchronousSendFailureUsesConditionalErrorUpdate() {
        doThrow(new AmqpException("连接断开")).when(template).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> producer.produceMsg(submission()));
        verify(cache).update(eq("j:r:7:10001"), any(JudgeResultCache.class), eq(5L), any());
    }

    private JudgeSubmitDTO submission() {
        JudgeSubmitDTO submission = new JudgeSubmitDTO();
        submission.setSubmitId(10001L);
        submission.setUserId(7L);
        submission.setQuestionId(9L);
        submission.setProgramType(0);
        return submission;
    }
}

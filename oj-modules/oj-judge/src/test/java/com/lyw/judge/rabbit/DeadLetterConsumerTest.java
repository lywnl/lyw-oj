package com.lyw.judge.rabbit;

import com.alibaba.fastjson2.JSON;
import com.lyw.common.redis.service.StateCacheService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterConsumerTest {
    @Test
    void protectedRealCacheStillAcknowledgesTheDuplicate() throws Exception {
        StateCacheService cache = mock(StateCacheService.class);
        DeadLetterListenerLifecycle lifecycle = mock(DeadLetterListenerLifecycle.class);
        Channel channel = mock(Channel.class);
        when(cache.update(anyString(), any(), anyLong(), any())).thenReturn(false);
        consumer(cache, lifecycle).consumeDeadLetter(message(), channel);
        verify(channel).basicAck(11L, false);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void threeCacheFailuresPauseWithoutAcknowledging() throws Exception {
        StateCacheService cache = mock(StateCacheService.class);
        DeadLetterListenerLifecycle lifecycle = mock(DeadLetterListenerLifecycle.class);
        Channel channel = mock(Channel.class);
        when(cache.update(anyString(), any(), anyLong(), any())).thenThrow(new IllegalStateException("Redis 故障"));
        consumer(cache, lifecycle).consumeDeadLetter(message(), channel);
        verify(cache, times(3)).update(anyString(), any(), anyLong(), any());
        verify(lifecycle).pause();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void ackFailureClosesChannelWithoutRetryingCacheWrite() throws Exception {
        StateCacheService cache = mock(StateCacheService.class);
        DeadLetterListenerLifecycle lifecycle = mock(DeadLetterListenerLifecycle.class);
        Channel channel = mock(Channel.class);
        doThrow(new IOException("断连")).when(channel).basicAck(11L, false);
        consumer(cache, lifecycle).consumeDeadLetter(message(), channel);
        verify(cache).update(anyString(), any(), anyLong(), any());
        verify(channel).abort();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void malformedMessageIsKeptWithoutCacheWrite() throws Exception {
        StateCacheService cache = mock(StateCacheService.class);
        DeadLetterListenerLifecycle lifecycle = mock(DeadLetterListenerLifecycle.class);
        Channel channel = mock(Channel.class);
        consumer(cache, lifecycle).consumeDeadLetter(new Message("not json".getBytes(StandardCharsets.UTF_8)), channel);
        verifyNoInteractions(cache);
        verify(lifecycle).pause();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private DeadLetterConsumer consumer(StateCacheService cache, DeadLetterListenerLifecycle lifecycle) {
        DeadLetterConsumer consumer = new DeadLetterConsumer();
        ReflectionTestUtils.setField(consumer, "stateCacheService", cache);
        ReflectionTestUtils.setField(consumer, "lifecycle", lifecycle);
        return consumer;
    }

    static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(11L);
        properties.setMessageId("duplicate-10001");
        return new Message(JSON.toJSONBytes(JudgeConsumerTest.submission()), properties);
    }
}

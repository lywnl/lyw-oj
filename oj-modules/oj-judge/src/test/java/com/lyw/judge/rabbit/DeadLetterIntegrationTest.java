package com.lyw.judge.rabbit;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.judge.config.DeadLetterListenerConfig;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.JudgeSystemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "oj.integration", matches = "true")
class DeadLetterIntegrationTest {
    @Test
    void cacheFailurePausesOnlyDeadListenerAndBrokerRetainsThenRecoversMessage() throws Exception {
        String prefix = "oj-idem-dlx-" + UUID.randomUUID();
        String workQueue = prefix + "-work", deadQueue = prefix + "-dead";
        String exchange = prefix + "-exchange";
        CachingConnectionFactory connection = new CachingConnectionFactory(System.getProperty("oj.rabbit.host", "rabbit"));
        connection.setUsername("oj_test");
        connection.setPassword("oj_test_password");
        LettuceConnectionFactory redisFactory = new LettuceConnectionFactory(System.getProperty("oj.redis.host", "redis"), 6379);
        redisFactory.afterPropertiesSet();
        StateCacheService realCache = new StateCacheService(redisFactory);
        StateCacheService failingCache = mock(StateCacheService.class);
        AtomicBoolean broken = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        when(failingCache.update(anyString(), any(), anyLong(), any())).thenAnswer(call -> {
            writes.incrementAndGet();
            if (broken.get()) throw new IllegalStateException("注入 Redis 写入故障");
            return realCache.update(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        RabbitAdmin admin = new RabbitAdmin(connection);
        RabbitTemplate sender = new RabbitTemplate(connection);
        long failedId = IdWorker.getId();
        try {
            admin.declareExchange(new DirectExchange(exchange, false, true));
            admin.declareQueue(QueueBuilder.nonDurable(workQueue).deadLetterExchange(exchange).deadLetterRoutingKey("dead").build());
            admin.declareQueue(QueueBuilder.nonDurable(deadQueue).build());
            admin.declareBinding(new Binding(deadQueue, Binding.DestinationType.QUEUE, exchange, "dead", null));
            DeadLetterConsumer dead = new DeadLetterConsumer();
            ReflectionTestUtils.setField(dead, "stateCacheService", failingCache);
            ReflectionTestUtils.setField(dead, "lifecycle", new DeadLetterListenerLifecycle(registry, executor));
            SimpleRabbitListenerContainerFactory deadFactory = new DeadLetterListenerConfig().deadLetterContainerFactory(
                    new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), connection);
            SimpleRabbitListenerEndpoint deadEndpoint = new SimpleRabbitListenerEndpoint();
            deadEndpoint.setId(DeadLetterListenerLifecycle.LISTENER_ID);
            deadEndpoint.setQueueNames(deadQueue);
            deadEndpoint.setMessageListener((ChannelAwareMessageListener) dead::consumeDeadLetter);
            registry.registerListenerContainer(deadEndpoint, deadFactory, true);

            AtomicInteger successfulBusiness = new AtomicInteger();
            IJudgeService service = submission -> {
                if (submission.getSubmitId() == failedId) throw new JudgeSystemException(CodeRunStatus.SYSTEM_BUSY);
                successfulBusiness.incrementAndGet();
                UserQuestionResultVO result = UserQuestionResultVO.noResult(submission.getSubmitId());
                result.setResultState(JudgeResultState.JUDGED);
                result.setPass(1);
                return result;
            };
            JudgeConsumer ordinary = new JudgeConsumer();
            ReflectionTestUtils.setField(ordinary, "judgeService", service);
            SimpleRabbitListenerContainerFactory ordinaryFactory = new SimpleRabbitListenerContainerFactory();
            ordinaryFactory.setConnectionFactory(connection);
            ordinaryFactory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            ordinaryFactory.setPrefetchCount(1);
            SimpleRabbitListenerEndpoint ordinaryEndpoint = new SimpleRabbitListenerEndpoint();
            ordinaryEndpoint.setId("ordinary-integration");
            ordinaryEndpoint.setQueueNames(workQueue);
            ordinaryEndpoint.setMessageListener((ChannelAwareMessageListener) (message, channel) -> ordinary.consumeMsg(
                    JSON.parseObject(message.getBody(), JudgeSubmitDTO.class), channel,
                    message.getMessageProperties().getDeliveryTag()));
            registry.registerListenerContainer(ordinaryEndpoint, ordinaryFactory, true);

            JudgeSubmitDTO failed = JudgeConsumerTest.submission();
            failed.setSubmitId(failedId);
            sender.send("", workQueue, new Message(JSON.toJSONBytes(failed)));
            await(() -> !registry.getListenerContainer(DeadLetterListenerLifecycle.LISTENER_ID).isRunning()
                    && ready(admin, deadQueue) == 1, 15000);
            assertEquals(3, writes.get());
            assertTrue(registry.getListenerContainer("ordinary-integration").isRunning());
            JudgeSubmitDTO another = JudgeConsumerTest.submission();
            another.setSubmitId(failedId + 1);
            sender.send("", workQueue, new Message(JSON.toJSONBytes(another)));
            await(() -> successfulBusiness.get() == 1, 5000);

            broken.set(false);
            registry.getListenerContainer(DeadLetterListenerLifecycle.LISTENER_ID).start();
            await(() -> writes.get() == 4 && realCache.get(CacheConstants.getJudgeResultKey(7L, failedId), JudgeResultCache.class) != null, 5000);
            registry.getListenerContainer(DeadLetterListenerLifecycle.LISTENER_ID).stop();
            assertEquals(0, ready(admin, deadQueue), "停止监听后消息不能因未确认再次回队列");
            assertEquals("SYSTEM_ERROR", realCache.get(CacheConstants.getJudgeResultKey(7L, failedId), JudgeResultCache.class).getCacheState());
        } finally {
            registry.stop();
            registry.destroy();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            admin.deleteQueue(workQueue);
            admin.deleteQueue(deadQueue);
            admin.deleteExchange(exchange);
            connection.destroy();
            redisFactory.destroy();
        }
    }

    private static int ready(RabbitAdmin admin, String queue) {
        var properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private static void await(BooleanSupplier condition, long timeout) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "在限定时间内未达到预期状态");
    }
}

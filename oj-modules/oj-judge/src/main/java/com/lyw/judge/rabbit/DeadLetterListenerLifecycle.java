package com.lyw.judge.rabbit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Component
@Slf4j
public class DeadLetterListenerLifecycle {
    public static final String LISTENER_ID = "judgeDeadLetterListener";
    private final RabbitListenerEndpointRegistry registry;
    private final ExecutorService executor;

    public DeadLetterListenerLifecycle(RabbitListenerEndpointRegistry registry,
            @Qualifier("deadLetterStopExecutor") ExecutorService executor) {
        this.registry = registry;
        this.executor = executor;
    }

    public void pause() {
        executor.execute(() -> {
            var container = registry.getListenerContainer(LISTENER_ID);
            if (container == null) {
                log.error("死信监听容器不存在：{}", LISTENER_ID);
                return;
            }
            container.stop();
            log.error("死信监听已暂停，未确认消息留在队列，恢复依赖后请人工重启判题服务");
        });
    }
}

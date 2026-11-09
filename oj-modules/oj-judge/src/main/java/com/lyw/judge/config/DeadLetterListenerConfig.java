package com.lyw.judge.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DeadLetterListenerConfig {
    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setContainerCustomizer(container -> {
            container.setShutdownTimeout(1000L);
            container.setForceCloseChannel(true);
        });
        return factory;
    }

    @Bean(name = "deadLetterStopExecutor", destroyMethod = "shutdown")
    public ExecutorService deadLetterStopExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "judge-dead-letter-stop");
            thread.setDaemon(true);
            return thread;
        });
    }
}

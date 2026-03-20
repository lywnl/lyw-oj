package com.lyw.common.rabbitmq.config;

import com.lyw.common.core.constants.RabbitMQConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    // ==================== 正常交换机、队列、绑定 ====================

    @Bean
    public DirectExchange ojExchange() {
        return new DirectExchange(RabbitMQConstants.OJ_EXCHANGE, true, false);
    }

    @Bean
    public Queue workQueue() {
        return QueueBuilder.durable(RabbitMQConstants.OJ_WORK_QUEUE)
                .deadLetterExchange(RabbitMQConstants.OJ_DLX_EXCHANGE)
                .deadLetterRoutingKey(RabbitMQConstants.OJ_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding workQueueBinding() {
        return BindingBuilder.bind(workQueue()).to(ojExchange()).with(RabbitMQConstants.OJ_ROUTING_KEY);
    }

    // ==================== 死信交换机、队列、绑定 ====================

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(RabbitMQConstants.OJ_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(RabbitMQConstants.OJ_DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxQueueBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(RabbitMQConstants.OJ_DLX_ROUTING_KEY);
    }

    // ==================== 消息序列化 ====================

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

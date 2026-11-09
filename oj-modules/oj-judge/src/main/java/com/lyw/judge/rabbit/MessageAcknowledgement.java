package com.lyw.judge.rabbit;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;

/** 确认异常只能结束故障通道，不得返回业务重试循环。 */
@Slf4j
final class MessageAcknowledgement {
    private MessageAcknowledgement() { }

    static void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("消息 ACK 失败，deliveryTag={}，关闭故障通道", tag);
            abort(channel);
        }
    }

    static void reject(Channel channel, long tag) {
        try {
            channel.basicNack(tag, false, false);
        } catch (Exception e) {
            log.warn("消息 NACK 失败，deliveryTag={}，关闭故障通道", tag);
            abort(channel);
        }
    }

    static void abort(Channel channel) {
        try {
            channel.abort();
        } catch (Exception e) {
            log.warn("故障消息通道关闭失败：{}", e.getMessage());
        }
    }
}

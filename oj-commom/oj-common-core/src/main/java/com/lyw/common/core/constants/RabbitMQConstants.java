package com.lyw.common.core.constants;

public class RabbitMQConstants {

    // 正常交换机、队列、路由键
    public static final String OJ_EXCHANGE = "oj.judge.exchange";
    public static final String OJ_WORK_QUEUE = "oj-work-queue";
    public static final String OJ_ROUTING_KEY = "oj.judge";

    // 死信交换机、队列、路由键
    public static final String OJ_DLX_EXCHANGE = "oj.judge.dlx.exchange";
    public static final String OJ_DLX_QUEUE = "oj-dead-letter-queue";
    public static final String OJ_DLX_ROUTING_KEY = "oj.judge.dlx";
}

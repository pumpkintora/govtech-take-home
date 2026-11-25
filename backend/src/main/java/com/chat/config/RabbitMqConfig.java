package com.chat.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for multi-node real-time fan-out.
 *
 * WHY Fanout + Per-Node Exclusive Auto-Delete Queue:
 * Every backend instance maintains its own in-memory map of local WebSocket TCP sockets.
 * When Alice (connected to node-1) sends a message to Bob (connected to node-2), node-1
 * persists the message to PostgreSQL first, then publishes a lightweight event to the
 * `chat.events` fanout exchange. Each live node consumes from its own exclusive queue
 * (`chat.node.<instanceId>`) and delivers only to sockets locally connected to that node.
 *
 * WHY non-durable / auto-delete queues:
 * PostgreSQL is the durable source of truth for messages. If a backend node crashes, its
 * TCP sockets are already dead; replaying stale MQ events to a restarted node would only
 * waste work because reconnecting clients perform cursor-based gap sync (`afterSeq`) from
 * PostgreSQL. Auto-delete prevents orphaned queues from accumulating during rolling deploys.
 */
@Configuration
@ConditionalOnProperty(name = "app.broker.type", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public FanoutExchange chatEventsExchange(AppProperties properties) {
        return new FanoutExchange(properties.getBroker().getExchange(), true, false);
    }

    @Bean
    public Queue nodeEventQueue(AppProperties properties) {
        String queueName = "chat.node." + properties.getInstanceId();
        // non-durable, exclusive to this node's connection, auto-delete when node disconnects
        return new Queue(queueName, false, true, true);
    }

    @Bean
    public Binding nodeEventBinding(Queue nodeEventQueue, FanoutExchange chatEventsExchange) {
        return BindingBuilder.bind(nodeEventQueue).to(chatEventsExchange);
    }
}

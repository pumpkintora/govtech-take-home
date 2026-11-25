package com.chat.realtime.broker;

import com.chat.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * RabbitMQ-backed multi-node message broker.
 *
 * Publishes real-time fan-out events to the shared `chat.events` fanout exchange; every backend
 * replica consumes from its own exclusive queue (`chat.node.<instanceId>`) and delivers only to
 * locally connected WebSocket clients.
 */
@Component
@ConditionalOnProperty(name = "app.broker.type", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqMessageBroker implements MessageBroker {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqMessageBroker.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();

    public RabbitMqMessageBroker(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            AppProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(BrokerEvent event) {
        // NOTE (Scaling trade-off): FanoutExchange broadcasts to every backend node, which is
        // simple and fast for 2-10 nodes. At much larger scale (50+ nodes), we would track which
        // node holds each user in Redis and route events only to the target user's node queue.
        try {
            String serialized = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(properties.getBroker().getExchange(), "", serialized);
        } catch (Exception e) {
            // Do not fail the sender's ACK if RabbitMQ experiences a transient blip: the message is
            // already durably committed in PostgreSQL, so recipients will recover via gap sync.
            log.error("Failed to publish event to RabbitMQ exchange={}", properties.getBroker().getExchange(), e);
        }
    }

    @Override
    public void subscribe(Consumer<BrokerEvent> listener) {
        listeners.add(listener);
    }

    @RabbitListener(queues = "#{nodeEventQueue.name}")
    public void onMessage(String rawMessage) {
        try {
            BrokerEvent event = objectMapper.readValue(rawMessage, BrokerEvent.class);
            for (Consumer<BrokerEvent> listener : listeners) {
                listener.accept(event);
            }
        } catch (Exception e) {
            log.error("Failed to deserialize RabbitMQ broker event", e);
        }
    }
}

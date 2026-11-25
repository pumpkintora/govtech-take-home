package com.chat.realtime.broker;

import java.util.function.Consumer;

/**
 * Decouples the real-time protocol handler from the underlying inter-node transport.
 * Swapping RabbitMQ for Kafka or Redis Pub/Sub only requires implementing this interface.
 */
public interface MessageBroker {

    void publish(BrokerEvent event);

    void subscribe(Consumer<BrokerEvent> listener);
}

package com.chat.realtime.broker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process MessageBroker implementation used for single-node local runs and automated tests.
 */
@Component
@ConditionalOnProperty(name = "app.broker.type", havingValue = "memory")
public class InMemoryMessageBroker implements MessageBroker {

    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    @Override
    public void subscribe(Consumer<BrokerEvent> listener) {
        listeners.add(listener);
    }
}

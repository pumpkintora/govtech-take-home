package com.chat.realtime.broker;

import java.util.List;
import java.util.UUID;

/**
 * Envelope published across backend instances via the MessageBroker (RabbitMQ fanout or in-memory).
 *
 * @param targetUserIds       Users who should receive the payload. Empty list means broadcast to all
 *                            online users (used for presence changes).
 * @param excludeConnectionId Optional connection ID to skip (e.g. sender's originating tab already
 *                            received a direct message.ack).
 * @param jsonPayload         Pre-serialized JSON frame so receiving nodes write directly without
 *                            re-serializing per socket.
 */
public record BrokerEvent(
        List<UUID> targetUserIds,
        UUID excludeConnectionId,
        String jsonPayload) {
}

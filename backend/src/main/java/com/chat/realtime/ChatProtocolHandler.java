package com.chat.realtime;

import com.chat.chat.ChatModels.ConversationRecord;
import com.chat.chat.ChatModels.MessageDto;
import com.chat.chat.ChatModels.PersistedMessageResult;
import com.chat.chat.ChatService;
import com.chat.config.AppProperties;
import com.chat.realtime.broker.BrokerEvent;
import com.chat.realtime.broker.MessageBroker;
import com.chat.realtime.ws.WebSocketConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-level real-time chat protocol layered cleanly on top of our raw RFC 6455 transport
 * and pluggable MessageBroker.
 *
 * Protocol envelope (versioned with `v: 1` for backward-compatible evolution during rolling deploys):
 *   Client -> Server:
 *     - message.send          { v: 1, type, conversationId, clientMessageId, body }
 *     - message.ack_delivered { v: 1, type, conversationId, upToSeq }
 *     - message.ack_read      { v: 1, type, conversationId, upToSeq }
 *     - typing.start / stop   { v: 1, type, conversationId }
 *
 *   Server -> Client:
 *     - session.ready         { v: 1, type, nodeId, userId, latestSeq }
 *     - message.ack           { v: 1, type, clientMessageId, message }
 *     - message.new           { v: 1, type, message }
 *     - message.status        { v: 1, type, conversationId, status, seqs, timestamp }
 *     - typing.update         { v: 1, type, conversationId, userId, typing }
 *     - presence.update       { v: 1, type, userId, online }
 *     - error                 { v: 1, type, code, message, clientMessageId? }
 */
@Component
public class ChatProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatProtocolHandler.class);
    private static final int PROTOCOL_VERSION = 1;

    private final ChatService chatService;
    private final ConnectionRegistry registry;
    private final MessageBroker broker;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public ChatProtocolHandler(
            ChatService chatService,
            ConnectionRegistry registry,
            MessageBroker broker,
            ObjectMapper objectMapper,
            AppProperties properties) {
        this.chatService = chatService;
        this.registry = registry;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.properties = properties;

        this.broker.subscribe(this::onBrokerEvent);
    }

    public void onConnect(WebSocketConnection connection) {
        registry.register(connection);
        int liveCount = chatService.onSocketConnected(connection.getConnectionId(), connection.getUserId());
        long latestSeq = chatService.getLatestSeq(connection.getUserId());

        sendEnvelope(connection, Map.of(
                "v", PROTOCOL_VERSION,
                "type", "session.ready",
                "nodeId", properties.getInstanceId(),
                "userId", connection.getUserId(),
                "latestSeq", latestSeq));

        // Only broadcast presence transition when the user goes from 0 -> 1 live devices globally.
        if (liveCount == 1) {
            publishPresence(connection.getUserId(), true, connection.getConnectionId());
        }
        log.info("WS connected user={} conn={} node={} globalLiveConns={}",
                connection.getUsername(), connection.getConnectionId(), properties.getInstanceId(), liveCount);
    }

    public void onDisconnect(WebSocketConnection connection) {
        registry.unregister(connection);
        int remaining = chatService.onSocketDisconnected(connection.getConnectionId(), connection.getUserId());

        // Only broadcast offline when ALL devices/tabs across ALL backend nodes have closed.
        if (remaining == 0) {
            publishPresence(connection.getUserId(), false, null);
        }
        log.info("WS disconnected user={} conn={} node={} remainingGlobalConns={}",
                connection.getUsername(), connection.getConnectionId(), properties.getInstanceId(), remaining);
    }

    public void onHeartbeat(WebSocketConnection connection) {
        chatService.onSocketHeartbeat(connection.getConnectionId());
    }

    public void sweepHeartbeats(long timeoutMs) {
        long now = System.currentTimeMillis();
        for (WebSocketConnection conn : registry.allConnections()) {
            if (!conn.isOpen()) {
                continue;
            }
            if (now - conn.getLastActivityMillis() > timeoutMs) {
                log.info("Reaping stale half-open socket user={} conn={} idleMs={}",
                        conn.getUsername(), conn.getConnectionId(), now - conn.getLastActivityMillis());
                conn.close(com.chat.realtime.ws.WsOpCode.CLOSE_GOING_AWAY, "Heartbeat timeout");
            } else {
                conn.sendPing();
            }
        }
    }

    public void closeAll(int statusCode, String reason) {
        for (WebSocketConnection conn : registry.allConnections()) {
            conn.close(statusCode, reason);
        }
    }

    public void onTextMessage(WebSocketConnection connection, String rawJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            sendError(connection, "INVALID_JSON", "Malformed JSON payload", null);
            return;
        }

        String type = root.path("type").asText("");
        switch (type) {
            case "message.send" -> handleMessageSend(connection, root);
            case "message.ack_delivered" -> handleDeliveryReceipt(connection, root, false);
            case "message.ack_read" -> handleDeliveryReceipt(connection, root, true);
            case "typing.start" -> handleTyping(connection, root, true);
            case "typing.stop" -> handleTyping(connection, root, false);
            default -> sendError(connection, "UNKNOWN_TYPE", "Unsupported event type: " + type, null);
        }
    }

    private void handleMessageSend(WebSocketConnection connection, JsonNode root) {
        // NOTE (MVP limitation): No per-user rate limiting yet. In production we would limit
        // messages/sec per user so one spamming client cannot overload the database or RabbitMQ.
        String clientMsgIdRaw = root.path("clientMessageId").asText(null);
        try {
            UUID conversationId = UUID.fromString(root.path("conversationId").asText(""));
            UUID clientMessageId = UUID.fromString(clientMsgIdRaw == null ? "" : clientMsgIdRaw);
            String body = root.path("body").asText("");

            // 1. Durable write to PostgreSQL FIRST (assigns monotonic `seq`, enforces idempotency).
            PersistedMessageResult result = chatService.persistMessage(
                    conversationId, connection.getUserId(), clientMessageId, body);
            MessageDto message = result.message();

            // 2. Always ACK the originating connection (even on duplicate retries so the client's
            //    optimistic "sending..." spinner resolves cleanly).
            sendEnvelope(connection, Map.of(
                    "v", PROTOCOL_VERSION,
                    "type", "message.ack",
                    "clientMessageId", clientMessageId,
                    "message", message));

            // 3. Fan out across RabbitMQ ONLY on first insert to avoid duplicate notification storms
            //    when a flaky mobile client retries an already-persisted message.
            // NOTE (MVP trade-off): If RabbitMQ is briefly unreachable right after step 1 succeeds,
            // the message is still safely stored in PostgreSQL — the recipient just won't get the
            // live push until they reconnect/refresh. In production, an outbox pattern ensures
            // guaranteed broker delivery.
            if (result.newlyInserted()) {
                String fanoutJson = toJson(Map.of(
                        "v", PROTOCOL_VERSION,
                        "type", "message.new",
                        "message", message));
                // Include BOTH sender (for their other open tabs/devices) and recipient.
                broker.publish(new BrokerEvent(
                        List.of(message.senderId(), message.recipientId()),
                        connection.getConnectionId(),
                        fanoutJson));
            }
        } catch (IllegalArgumentException | ResponseStatusException e) {
            sendError(connection, "SEND_REJECTED", e.getMessage(), clientMsgIdRaw);
        } catch (Exception e) {
            log.error("Failed to process message.send for user={}", connection.getUsername(), e);
            sendError(connection, "INTERNAL_ERROR", "Could not persist message", clientMsgIdRaw);
        }
    }

    private void handleDeliveryReceipt(WebSocketConnection connection, JsonNode root, boolean isRead) {
        try {
            UUID conversationId = UUID.fromString(root.path("conversationId").asText(""));
            long upToSeq = root.path("upToSeq").asLong(-1);
            if (upToSeq <= 0) {
                return;
            }

            List<MessageDto> updated = isRead
                    ? chatService.markRead(conversationId, connection.getUserId(), upToSeq)
                    : chatService.markDelivered(conversationId, connection.getUserId(), upToSeq);

            if (updated.isEmpty()) {
                return;
            }

            UUID senderId = updated.getFirst().senderId();
            UUID recipientId = updated.getFirst().recipientId();
            List<Long> seqs = updated.stream().map(MessageDto::seq).toList();
            Instant timestamp = isRead ? updated.getLast().readAt() : updated.getLast().deliveredAt();

            String payload = toJson(Map.of(
                    "v", PROTOCOL_VERSION,
                    "type", "message.status",
                    "conversationId", conversationId,
                    "status", isRead ? "READ" : "DELIVERED",
                    "seqs", seqs,
                    "timestamp", timestamp != null ? timestamp : Instant.now()));

            // Fan out to both sender (to update checkmarks) and recipient's other devices (to clear unread badges).
            broker.publish(new BrokerEvent(List.of(senderId, recipientId), null, payload));
        } catch (Exception e) {
            log.debug("Ignoring invalid receipt from user={}: {}", connection.getUsername(), e.getMessage());
        }
    }

    private void handleTyping(WebSocketConnection connection, JsonNode root, boolean typing) {
        try {
            UUID conversationId = UUID.fromString(root.path("conversationId").asText(""));
            ConversationRecord conv = chatService.requireParticipant(conversationId, connection.getUserId());
            UUID peerId = conv.otherParticipant(connection.getUserId());

            // Ephemeral event: bypasses PostgreSQL completely and routes straight through RabbitMQ.
            String payload = toJson(Map.of(
                    "v", PROTOCOL_VERSION,
                    "type", "typing.update",
                    "conversationId", conversationId,
                    "userId", connection.getUserId(),
                    "typing", typing));
            broker.publish(new BrokerEvent(List.of(peerId), null, payload));
        } catch (Exception e) {
            log.debug("Ignoring invalid typing event from user={}: {}", connection.getUsername(), e.getMessage());
        }
    }

    private void publishPresence(UUID userId, boolean online, UUID excludeConnectionId) {
        String payload = toJson(Map.of(
                "v", PROTOCOL_VERSION,
                "type", "presence.update",
                "userId", userId,
                "online", online));
        broker.publish(new BrokerEvent(List.of(), excludeConnectionId, payload));
    }

    private void onBrokerEvent(BrokerEvent event) {
        if (event.targetUserIds() == null || event.targetUserIds().isEmpty()) {
            registry.broadcastAll(event.excludeConnectionId(), event.jsonPayload());
        } else {
            registry.deliverToUsers(event.targetUserIds(), event.excludeConnectionId(), event.jsonPayload());
        }
    }

    private void sendEnvelope(WebSocketConnection connection, Map<String, Object> envelope) {
        connection.sendText(toJson(envelope));
    }

    private void sendError(WebSocketConnection connection, String code, String message, String clientMessageId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", PROTOCOL_VERSION);
        envelope.put("type", "error");
        envelope.put("code", code);
        envelope.put("message", message == null ? "Request failed" : message);
        if (clientMessageId != null) {
            envelope.put("clientMessageId", clientMessageId);
        }
        sendEnvelope(connection, envelope);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON envelope", e);
        }
    }
}

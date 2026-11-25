package com.chat.realtime;

import com.chat.realtime.ws.WebSocketConnection;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of WebSocket connections currently terminated on THIS backend replica.
 * Supports multiple concurrent tabs/devices per user (e.g., mobile + desktop simultaneously).
 *
 * WHY ConcurrentHashMap.compute / computeIfPresent:
 * A naive `get()` + `add()` combined with `remove()` when empty suffers from a classic check-then-act
 * race condition: Thread A sees the user's set became empty and removes the key from the map at the
 * exact moment Thread B adds a newly reconnected socket to that orphaned set — silently losing all
 * future deliveries to Thread B's socket. Using atomic map compute methods prevents orphaned sets.
 */
@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<UUID, Set<WebSocketConnection>> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WebSocketConnection> byId = new ConcurrentHashMap<>();

    public void register(WebSocketConnection connection) {
        byId.put(connection.getConnectionId(), connection);
        byUser.compute(connection.getUserId(), (userId, existing) -> {
            Set<WebSocketConnection> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
            set.add(connection);
            return set;
        });
    }

    public void unregister(WebSocketConnection connection) {
        byId.remove(connection.getConnectionId());
        byUser.computeIfPresent(connection.getUserId(), (userId, existing) -> {
            existing.remove(connection);
            return existing.isEmpty() ? null : existing;
        });
    }

    public Collection<WebSocketConnection> allConnections() {
        return List.copyOf(byId.values());
    }

    public void deliverToUsers(List<UUID> userIds, UUID excludeConnectionId, String jsonPayload) {
        for (UUID userId : userIds) {
            Set<WebSocketConnection> sockets = byUser.get(userId);
            if (sockets == null || sockets.isEmpty()) {
                continue;
            }
            for (WebSocketConnection conn : sockets) {
                if (excludeConnectionId != null && excludeConnectionId.equals(conn.getConnectionId())) {
                    continue;
                }
                conn.sendText(jsonPayload);
            }
        }
    }

    public void broadcastAll(UUID excludeConnectionId, String jsonPayload) {
        for (WebSocketConnection conn : byId.values()) {
            if (excludeConnectionId != null && excludeConnectionId.equals(conn.getConnectionId())) {
                continue;
            }
            conn.sendText(jsonPayload);
        }
    }
}

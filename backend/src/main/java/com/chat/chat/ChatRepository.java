package com.chat.chat;

import com.chat.chat.ChatModels.ConversationRecord;
import com.chat.chat.ChatModels.ConversationView;
import com.chat.chat.ChatModels.MessageDto;
import com.chat.chat.ChatModels.PersistedMessageResult;
import com.chat.chat.ChatModels.UserSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit PostgreSQL data access layer using JdbcTemplate.
 *
 * WHY JdbcTemplate instead of JPA/Hibernate for a messaging core:
 * 1. Exact control over PostgreSQL-specific concurrency primitives (`ON CONFLICT DO NOTHING`,
 *    partial unread indexes, `RETURNING`, cursor pagination on `seq`) without ORM dirty-checking
 *    or accidental N+1 queries.
 * 2. Predictable SQL makes query plans easy to inspect and optimize under high message throughput.
 */
@Repository
public class ChatRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<MessageDto> MESSAGE_MAPPER = (rs, i) -> new MessageDto(
            rs.getLong("seq"),
            rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("sender_id", UUID.class),
            rs.getObject("recipient_id", UUID.class),
            rs.getObject("client_msg_id", UUID.class),
            rs.getString("body"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("delivered_at")),
            toInstant(rs.getTimestamp("read_at")));

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserSummary> listOtherUsers(UUID currentUserId, int presenceTtlSeconds) {
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name,
                       EXISTS (
                           SELECT 1 FROM active_connections ac
                           WHERE ac.user_id = u.id
                             AND ac.last_heartbeat_at > NOW() - (? * INTERVAL '1 second')
                       ) AS online
                FROM users u
                WHERE u.id <> ?
                ORDER BY u.display_name ASC
                """,
                (rs, i) -> new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getBoolean("online")),
                presenceTtlSeconds, currentUserId);
    }

    public Optional<UserSummary> findUserById(UUID userId, int presenceTtlSeconds) {
        List<UserSummary> rows = jdbc.query("""
                SELECT u.id, u.username, u.display_name,
                       EXISTS (
                           SELECT 1 FROM active_connections ac
                           WHERE ac.user_id = u.id
                             AND ac.last_heartbeat_at > NOW() - (? * INTERVAL '1 second')
                       ) AS online
                FROM users u
                WHERE u.id = ?
                """,
                (rs, i) -> new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getBoolean("online")),
                presenceTtlSeconds, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Atomically gets or creates the single canonical 1-to-1 conversation for two users.
     * Ordering UUIDs canonically ensures concurrent starts from both sides converge on one row.
     *
     * WHY unsigned comparison instead of `u1.compareTo(u2)`:
     * Java's `UUID.compareTo()` compares `mostSigBits` / `leastSigBits` as SIGNED 64-bit longs
     * (JDK-7025832), so `8000...` sorts BEFORE `7fff...`. PostgreSQL's `uuid < uuid` compares
     * UUIDs as UNSIGNED 128-bit values (`7fff... < 8000...`). Using `Long.compareUnsigned`
     * keeps Java's ordering 100% consistent with PostgreSQL's `CHECK (user_a_id < user_b_id)`.
     */
    public ConversationRecord getOrCreateConversation(UUID u1, UUID u2) {
        UUID userA = compareUnsigned(u1, u2) < 0 ? u1 : u2;
        UUID userB = compareUnsigned(u1, u2) < 0 ? u2 : u1;

        jdbc.update("""
                INSERT INTO conversations (id, user_a_id, user_b_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_a_id, user_b_id) DO NOTHING
                """,
                UUID.randomUUID(), userA, userB);

        return jdbc.queryForObject("""
                SELECT id, user_a_id, user_b_id, created_at
                FROM conversations
                WHERE user_a_id = ? AND user_b_id = ?
                """,
                (rs, i) -> new ConversationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_a_id", UUID.class),
                        rs.getObject("user_b_id", UUID.class),
                        toInstant(rs.getTimestamp("created_at"))),
                userA, userB);
    }

    public Optional<ConversationRecord> findConversationById(UUID conversationId) {
        List<ConversationRecord> rows = jdbc.query("""
                SELECT id, user_a_id, user_b_id, created_at
                FROM conversations
                WHERE id = ?
                """,
                (rs, i) -> new ConversationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_a_id", UUID.class),
                        rs.getObject("user_b_id", UUID.class),
                        toInstant(rs.getTimestamp("created_at"))),
                conversationId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ConversationView> listConversationsForUser(UUID currentUserId, int presenceTtlSeconds) {
        return jdbc.query("""
                SELECT c.id AS conv_id,
                       c.created_at AS conv_created_at,
                       peer.id AS peer_id,
                       peer.username AS peer_username,
                       peer.display_name AS peer_display_name,
                       EXISTS (
                           SELECT 1 FROM active_connections ac
                           WHERE ac.user_id = peer.id
                             AND ac.last_heartbeat_at > NOW() - (? * INTERVAL '1 second')
                       ) AS peer_online,
                       lm.seq AS lm_seq,
                       lm.id AS lm_id,
                       lm.sender_id AS lm_sender_id,
                       lm.recipient_id AS lm_recipient_id,
                       lm.client_msg_id AS lm_client_msg_id,
                       lm.body AS lm_body,
                       lm.created_at AS lm_created_at,
                       lm.delivered_at AS lm_delivered_at,
                       lm.read_at AS lm_read_at,
                       (SELECT COUNT(*) FROM messages um
                        WHERE um.conversation_id = c.id
                          AND um.recipient_id = ?
                          AND um.read_at IS NULL) AS unread_count
                FROM conversations c
                JOIN users peer ON peer.id = CASE WHEN c.user_a_id = ? THEN c.user_b_id ELSE c.user_a_id END
                LEFT JOIN LATERAL (
                    SELECT seq, id, sender_id, recipient_id, client_msg_id, body, created_at, delivered_at, read_at
                    FROM messages
                    WHERE conversation_id = c.id
                    ORDER BY seq DESC
                    LIMIT 1
                ) lm ON TRUE
                WHERE c.user_a_id = ? OR c.user_b_id = ?
                ORDER BY COALESCE(lm.seq, 0) DESC, c.created_at DESC
                """,
                (rs, i) -> {
                    UUID convId = rs.getObject("conv_id", UUID.class);
                    UserSummary peer = new UserSummary(
                            rs.getObject("peer_id", UUID.class),
                            rs.getString("peer_username"),
                            rs.getString("peer_display_name"),
                            rs.getBoolean("peer_online"));
                    MessageDto lastMsg = null;
                    long lmSeq = rs.getLong("lm_seq");
                    if (!rs.wasNull()) {
                        lastMsg = new MessageDto(
                                lmSeq,
                                rs.getObject("lm_id", UUID.class),
                                convId,
                                rs.getObject("lm_sender_id", UUID.class),
                                rs.getObject("lm_recipient_id", UUID.class),
                                rs.getObject("lm_client_msg_id", UUID.class),
                                rs.getString("lm_body"),
                                toInstant(rs.getTimestamp("lm_created_at")),
                                toInstant(rs.getTimestamp("lm_delivered_at")),
                                toInstant(rs.getTimestamp("lm_read_at")));
                    }
                    return new ConversationView(
                            convId,
                            peer,
                            lastMsg,
                            rs.getInt("unread_count"),
                            toInstant(rs.getTimestamp("conv_created_at")));
                },
                presenceTtlSeconds, currentUserId, currentUserId, currentUserId, currentUserId);
    }

    /**
     * Persists a message idempotently using (sender_id, client_msg_id) uniqueness.
     *
     * WHY: Under flaky mobile networks, a client frequently retries sending a message when the
     * ACK frame is lost in flight. `ON CONFLICT DO NOTHING` guarantees at-most-once persistence
     * while returning the exact original message and server sequence number so the client reconciles.
     */
    public PersistedMessageResult insertMessageIdempotent(
            UUID conversationId,
            UUID senderId,
            UUID recipientId,
            UUID clientMessageId,
            String body) {
        List<MessageDto> inserted = jdbc.query("""
                INSERT INTO messages (id, conversation_id, sender_id, recipient_id, client_msg_id, body)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (sender_id, client_msg_id) DO NOTHING
                RETURNING seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                          created_at, delivered_at, read_at
                """,
                MESSAGE_MAPPER,
                UUID.randomUUID(), conversationId, senderId, recipientId, clientMessageId, body);

        if (!inserted.isEmpty()) {
            return new PersistedMessageResult(inserted.getFirst(), true);
        }

        MessageDto existing = jdbc.queryForObject("""
                SELECT seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                       created_at, delivered_at, read_at
                FROM messages
                WHERE sender_id = ? AND client_msg_id = ?
                """,
                MESSAGE_MAPPER,
                senderId, clientMessageId);
        return new PersistedMessageResult(existing, false);
    }

    public List<MessageDto> fetchConversationMessages(UUID conversationId, Long beforeSeq, int limit) {
        if (beforeSeq == null) {
            return jdbc.query("""
                    SELECT * FROM (
                        SELECT seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                               created_at, delivered_at, read_at
                        FROM messages
                        WHERE conversation_id = ?
                        ORDER BY seq DESC
                        LIMIT ?
                    ) sub ORDER BY seq ASC
                    """,
                    MESSAGE_MAPPER,
                    conversationId, limit);
        }
        return jdbc.query("""
                SELECT * FROM (
                    SELECT seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                           created_at, delivered_at, read_at
                    FROM messages
                    WHERE conversation_id = ? AND seq < ?
                    ORDER BY seq DESC
                    LIMIT ?
                ) sub ORDER BY seq ASC
                """,
                MESSAGE_MAPPER,
                conversationId, beforeSeq, limit);
    }

    /**
     * Cursor-based gap recovery for reconnecting clients (`afterSeq`).
     * Fetches any messages involving `userId` committed after their last observed `seq`.
     */
    public List<MessageDto> fetchMissedMessagesForUser(UUID userId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                       created_at, delivered_at, read_at
                FROM messages
                WHERE seq > ? AND (sender_id = ? OR recipient_id = ?)
                ORDER BY seq ASC
                LIMIT ?
                """,
                MESSAGE_MAPPER,
                afterSeq, userId, userId, limit);
    }

    public long getLatestSeqForUser(UUID userId) {
        Long maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq), 0)
                FROM messages
                WHERE sender_id = ? OR recipient_id = ?
                """,
                Long.class,
                userId, userId);
        return maxSeq == null ? 0L : maxSeq;
    }

    /**
     * Marks messages up to `upToSeq` as delivered for the recipient and returns updated rows.
     */
    public List<MessageDto> markDeliveredUpTo(UUID conversationId, UUID recipientId, long upToSeq) {
        return jdbc.query("""
                UPDATE messages
                SET delivered_at = COALESCE(delivered_at, NOW())
                WHERE conversation_id = ?
                  AND recipient_id = ?
                  AND seq <= ?
                  AND delivered_at IS NULL
                RETURNING seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                          created_at, delivered_at, read_at
                """,
                MESSAGE_MAPPER,
                conversationId, recipientId, upToSeq);
    }

    /**
     * Marks messages up to `upToSeq` as read (and delivered if not yet marked) for the recipient.
     */
    public List<MessageDto> markReadUpTo(UUID conversationId, UUID recipientId, long upToSeq) {
        return jdbc.query("""
                UPDATE messages
                SET delivered_at = COALESCE(delivered_at, NOW()),
                    read_at = COALESCE(read_at, NOW())
                WHERE conversation_id = ?
                  AND recipient_id = ?
                  AND seq <= ?
                  AND read_at IS NULL
                RETURNING seq, id, conversation_id, sender_id, recipient_id, client_msg_id, body,
                          created_at, delivered_at, read_at
                """,
                MESSAGE_MAPPER,
                conversationId, recipientId, upToSeq);
    }

    // ---- Distributed Presence Tracking (Multi-Node + Multi-Device) ----

    /**
     * Registers an active socket connection and returns the user's total live connection count
     * across ALL backend replicas. Transition 0 -> 1 triggers an "online" broadcast.
     */
    public int registerActiveConnection(UUID connectionId, UUID userId, String nodeId, int ttlSeconds) {
        jdbc.update("""
                INSERT INTO active_connections (connection_id, user_id, node_id, last_heartbeat_at)
                VALUES (?, ?, ?, NOW())
                ON CONFLICT (connection_id) DO UPDATE SET last_heartbeat_at = NOW()
                """,
                connectionId, userId, nodeId);
        return countLiveConnections(userId, ttlSeconds);
    }

    public void touchConnectionHeartbeat(UUID connectionId) {
        // NOTE (Scaling trade-off): Updating PostgreSQL every 15s per connected user works fine
        // for an MVP, but at tens of thousands of users we would move online heartbeats into
        // Redis TTL keys so PostgreSQL doesn't absorb constant presence writes.
        jdbc.update("UPDATE active_connections SET last_heartbeat_at = NOW() WHERE connection_id = ?", connectionId);
    }

    /**
     * Removes a disconnected socket and returns the remaining live connection count across ALL
     * nodes. Only when this hits 0 do we broadcast "offline" — avoiding false offline flicker
     * when a user closes 1 of 2 open tabs/devices.
     */
    public int removeActiveConnection(UUID connectionId, UUID userId, int ttlSeconds) {
        jdbc.update("DELETE FROM active_connections WHERE connection_id = ?", connectionId);
        return countLiveConnections(userId, ttlSeconds);
    }

    public int countLiveConnections(UUID userId, int ttlSeconds) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM active_connections
                WHERE user_id = ?
                  AND last_heartbeat_at > NOW() - (? * INTERVAL '1 second')
                """,
                Integer.class,
                userId, ttlSeconds);
        return count == null ? 0 : count;
    }

    static int compareUnsigned(UUID a, UUID b) {
        int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

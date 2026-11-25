package com.chat.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared immutable DTO records for REST APIs and WebSocket payloads.
 */
public final class ChatModels {

    private ChatModels() {
    }

    public record UserSummary(
            UUID id,
            String username,
            String displayName,
            boolean online) {
    }

    public record ConversationRecord(
            UUID id,
            UUID userAId,
            UUID userBId,
            Instant createdAt) {

        public boolean involves(UUID userId) {
            return userAId.equals(userId) || userBId.equals(userId);
        }

        public UUID otherParticipant(UUID userId) {
            return userAId.equals(userId) ? userBId : userAId;
        }
    }

    public record MessageDto(
            long seq,
            UUID id,
            UUID conversationId,
            UUID senderId,
            UUID recipientId,
            UUID clientMessageId,
            String body,
            Instant createdAt,
            Instant deliveredAt,
            Instant readAt) {
    }

    public record ConversationView(
            UUID id,
            UserSummary peer,
            MessageDto lastMessage,
            int unreadCount,
            Instant createdAt) {
    }

    public record PersistedMessageResult(
            MessageDto message,
            boolean newlyInserted) {
    }
}

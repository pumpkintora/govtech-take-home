package com.chat.chat;

import com.chat.chat.ChatModels.ConversationRecord;
import com.chat.chat.ChatModels.ConversationView;
import com.chat.chat.ChatModels.MessageDto;
import com.chat.chat.ChatModels.PersistedMessageResult;
import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatRepository repository;
    private final AppProperties properties;

    public ChatService(ChatRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public int presenceTtlSeconds() {
        // Slightly larger than WS heartbeat timeout to avoid transient false-offline flaps
        // between a Pong arriving and the DB heartbeat update committing.
        return properties.getWs().getHeartbeatTimeoutSeconds() + 15;
    }

    public List<UserSummary> listDirectoryUsers(UUID currentUserId) {
        return repository.listOtherUsers(currentUserId, presenceTtlSeconds());
    }

    public List<ConversationView> listConversations(UUID currentUserId) {
        return repository.listConversationsForUser(currentUserId, presenceTtlSeconds());
    }

    public ConversationView startOrGetConversation(UUID currentUserId, UUID targetUserId) {
        if (targetUserId == null || targetUserId.equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot start a 1-to-1 chat with yourself");
        }
        UserSummary peer = repository.findUserById(targetUserId, presenceTtlSeconds())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found"));

        ConversationRecord conv = repository.getOrCreateConversation(currentUserId, targetUserId);
        List<MessageDto> recent = repository.fetchConversationMessages(conv.id(), null, 1);
        MessageDto lastMessage = recent.isEmpty() ? null : recent.getLast();
        return new ConversationView(conv.id(), peer, lastMessage, 0, conv.createdAt());
    }

    public ConversationRecord requireParticipant(UUID conversationId, UUID userId) {
        ConversationRecord conv = repository.findConversationById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.involves(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant in this conversation");
        }
        return conv;
    }

    public List<MessageDto> getMessages(UUID conversationId, UUID userId, Long beforeSeq, int requestedLimit) {
        requireParticipant(conversationId, userId);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
        return repository.fetchConversationMessages(conversationId, beforeSeq, limit);
    }

    public List<MessageDto> syncMessagesAfter(UUID userId, long afterSeq, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return repository.fetchMissedMessagesForUser(userId, Math.max(0, afterSeq), limit);
    }

    public long getLatestSeq(UUID userId) {
        return repository.getLatestSeqForUser(userId);
    }

    public PersistedMessageResult persistMessage(
            UUID conversationId,
            UUID senderId,
            UUID clientMessageId,
            String rawBody) {
        if (clientMessageId == null) {
            throw new IllegalArgumentException("clientMessageId is required");
        }
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isEmpty() || body.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message body must be between 1 and " + MAX_MESSAGE_LENGTH + " characters");
        }
        ConversationRecord conv = requireParticipant(conversationId, senderId);
        UUID recipientId = conv.otherParticipant(senderId);

        // Persist to PostgreSQL BEFORE publishing to RabbitMQ/WebSockets.
        // WHY: Guarantees durability and strict monotonic `seq` ordering. If we fanned out
        // before DB commit and the commit failed (or crashed), the recipient would see a ghost
        // message that disappears on refresh.
        return repository.insertMessageIdempotent(conversationId, senderId, recipientId, clientMessageId, body);
    }

    public List<MessageDto> markDelivered(UUID conversationId, UUID recipientId, long upToSeq) {
        requireParticipant(conversationId, recipientId);
        return repository.markDeliveredUpTo(conversationId, recipientId, upToSeq);
    }

    public List<MessageDto> markRead(UUID conversationId, UUID recipientId, long upToSeq) {
        requireParticipant(conversationId, recipientId);
        return repository.markReadUpTo(conversationId, recipientId, upToSeq);
    }

    public int onSocketConnected(UUID connectionId, UUID userId) {
        return repository.registerActiveConnection(
                connectionId, userId, properties.getInstanceId(), presenceTtlSeconds());
    }

    public void onSocketHeartbeat(UUID connectionId) {
        repository.touchConnectionHeartbeat(connectionId);
    }

    public int onSocketDisconnected(UUID connectionId, UUID userId) {
        return repository.removeActiveConnection(connectionId, userId, presenceTtlSeconds());
    }
}

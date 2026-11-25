package com.chat.chat;

import com.chat.auth.SessionAuthFilter;
import com.chat.chat.ChatModels.ConversationView;
import com.chat.chat.ChatModels.MessageDto;
import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final AppProperties properties;

    public ChatController(ChatService chatService, AppProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    public record StartConversationRequest(UUID targetUserId) {
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "nodeId", properties.getInstanceId());
    }

    @GetMapping("/users")
    public List<UserSummary> listUsers(HttpServletRequest request) {
        UserSummary current = currentUser(request);
        return chatService.listDirectoryUsers(current.id());
    }

    @GetMapping("/conversations")
    public List<ConversationView> listConversations(HttpServletRequest request) {
        UserSummary current = currentUser(request);
        return chatService.listConversations(current.id());
    }

    @PostMapping("/conversations")
    public ConversationView startConversation(
            @RequestBody StartConversationRequest body,
            HttpServletRequest request) {
        UserSummary current = currentUser(request);
        return chatService.startOrGetConversation(current.id(), body.targetUserId());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageDto> getMessages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        UserSummary current = currentUser(request);
        return chatService.getMessages(conversationId, current.id(), beforeSeq, limit);
    }

    /**
     * Gap recovery endpoint called automatically by WebSocket clients upon reconnecting.
     */
    @GetMapping("/messages/sync")
    public Map<String, Object> syncMessages(
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        UserSummary current = currentUser(request);
        List<MessageDto> missed = chatService.syncMessagesAfter(current.id(), afterSeq, limit);
        long latestSeq = missed.isEmpty() ? chatService.getLatestSeq(current.id()) : missed.getLast().seq();
        return Map.of(
                "messages", missed,
                "latestSeq", latestSeq);
    }

    private UserSummary currentUser(HttpServletRequest request) {
        return (UserSummary) request.getAttribute(SessionAuthFilter.AUTH_USER_ATTR);
    }
}

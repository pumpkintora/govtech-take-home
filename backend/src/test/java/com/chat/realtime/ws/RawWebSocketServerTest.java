package com.chat.realtime.ws;

import com.chat.auth.AuthService;
import com.chat.chat.ChatModels.MessageDto;
import com.chat.chat.ChatModels.PersistedMessageResult;
import com.chat.chat.ChatModels.UserSummary;
import com.chat.chat.ChatService;
import com.chat.config.AppProperties;
import com.chat.realtime.ChatProtocolHandler;
import com.chat.realtime.ConnectionRegistry;
import com.chat.realtime.broker.InMemoryMessageBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end TCP protocol test: connects Java's standard RFC 6455 WebSocket client
 * (java.net.http.HttpClient) directly to our custom RawWebSocketServer over localhost TCP
 * sockets to verify handshake, authentication, framing, ACK, and cross-client delivery.
 */
class RawWebSocketServerTest {

    private RawWebSocketServer server;
    private AuthService authService;
    private ChatService chatService;
    private ObjectMapper objectMapper;

    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID convId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setInstanceId("test-node-1");
        props.getWs().setPort(0); // Bind to ephemeral OS port
        props.getWs().setAllowedOrigins("");

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        authService = mock(AuthService.class);
        chatService = mock(ChatService.class);

        when(authService.resolveSession("alice-token"))
                .thenReturn(Optional.of(new UserSummary(aliceId, "alice", "Alice", true)));
        when(authService.resolveSession("bob-token"))
                .thenReturn(Optional.of(new UserSummary(bobId, "bob", "Bob", true)));
        when(chatService.onSocketConnected(any(), any())).thenReturn(1);
        when(chatService.onSocketDisconnected(any(), any())).thenReturn(0);
        when(chatService.getLatestSeq(any())).thenReturn(0L);

        InMemoryMessageBroker broker = new InMemoryMessageBroker();
        ConnectionRegistry registry = new ConnectionRegistry();
        ChatProtocolHandler handler = new ChatProtocolHandler(chatService, registry, broker, objectMapper, props);

        server = new RawWebSocketServer(props, authService, handler);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void deliversRealTimeMessageBetweenTwoAuthenticatedClientsOverRawTcp() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        MessageDto persisted = new MessageDto(
                42L, UUID.randomUUID(), convId, aliceId, bobId, clientMsgId,
                "Hello from Alice over raw RFC 6455!", Instant.now(), null, null);
        when(chatService.persistMessage(eq(convId), eq(aliceId), eq(clientMsgId), eq("Hello from Alice over raw RFC 6455!")))
                .thenReturn(new PersistedMessageResult(persisted, true));

        BlockingQueue<String> aliceInbox = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobInbox = new LinkedBlockingQueue<>();

        URI wsUri = URI.create("ws://127.0.0.1:" + server.getBoundPort() + "/ws");
        HttpClient client = HttpClient.newHttpClient();

        WebSocket aliceWs = connect(client, wsUri, "alice-token", aliceInbox);
        WebSocket bobWs = connect(client, wsUri, "bob-token", bobInbox);

        // Both clients receive session.ready upon upgrade
        JsonNode aliceReady = objectMapper.readTree(aliceInbox.poll(3, TimeUnit.SECONDS));
        assertEquals("session.ready", aliceReady.path("type").asText());
        assertEquals("test-node-1", aliceReady.path("nodeId").asText());

        JsonNode bobReady = objectMapper.readTree(bobInbox.poll(3, TimeUnit.SECONDS));
        assertEquals("session.ready", bobReady.path("type").asText());

        // Alice sends a 1-to-1 message
        String sendPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "v", 1,
                "type", "message.send",
                "conversationId", convId.toString(),
                "clientMessageId", clientMsgId.toString(),
                "body", "Hello from Alice over raw RFC 6455!"));
        aliceWs.sendText(sendPayload, true).join();

        // Alice receives message.ack with server seq=42
        JsonNode ack = pollUntilType(aliceInbox, "message.ack");
        assertNotNull(ack);
        assertEquals(42L, ack.path("message").path("seq").asLong());
        assertEquals(clientMsgId.toString(), ack.path("clientMessageId").asText());

        // Bob receives message.new in real time
        JsonNode delivered = pollUntilType(bobInbox, "message.new");
        assertNotNull(delivered);
        assertEquals("Hello from Alice over raw RFC 6455!", delivered.path("message").path("body").asText());
        assertEquals(42L, delivered.path("message").path("seq").asLong());

        aliceWs.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        bobWs.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private WebSocket connect(HttpClient client, URI uri, String token, BlockingQueue<String> inbox) {
        return client.newWebSocketBuilder()
                .header("Cookie", "CHAT_SESSION=" + token)
                .buildAsync(uri, new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            inbox.offer(partial.toString());
                            partial.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();
    }

    private JsonNode pollUntilType(BlockingQueue<String> queue, String expectedType) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String raw = queue.poll(200, TimeUnit.MILLISECONDS);
            if (raw != null) {
                JsonNode node = objectMapper.readTree(raw);
                if (expectedType.equals(node.path("type").asText())) {
                    return node;
                }
            }
        }
        return null;
    }
}

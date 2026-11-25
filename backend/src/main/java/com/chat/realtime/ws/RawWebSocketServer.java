package com.chat.realtime.ws;

import com.chat.auth.AuthService;
import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import com.chat.realtime.ChatProtocolHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Custom RFC 6455 WebSocket server built directly on java.net.ServerSocket + Java 21 Virtual Threads.
 *
 * WHY Java 21 Virtual Threads instead of classic platform thread pools or reactive callbacks:
 * - A classic 1-platform-thread-per-socket server exhausts memory/OS threads around ~2k-5k sockets.
 * - Virtual threads park cheaply on blocking socket I/O (few KB per connection), supporting tens of
 *   thousands of concurrent mobile + desktop sockets per JVM while keeping linear, readable code
 *   that is straightforward to debug and extend.
 * - For extreme scale (>500k mostly-idle sockets/node), the next evolution step is non-blocking
 *   java.nio.channels.Selector / epoll (documented in ARCHITECTURE.md).
 */
@Component
public class RawWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(RawWebSocketServer.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    private final AppProperties properties;
    private final AuthService authService;
    private final ChatProtocolHandler protocolHandler;

    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat-reaper");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public RawWebSocketServer(
            AppProperties properties,
            AuthService authService,
            ChatProtocolHandler protocolHandler) {
        this.properties = properties;
        this.authService = authService;
        this.protocolHandler = protocolHandler;
    }

    @PostConstruct
    public void start() throws IOException {
        if (properties.getWs().getPort() < 0) {
            log.info("RawWebSocketServer disabled (ws.port < 0)");
            return;
        }
        serverSocket = new ServerSocket(properties.getWs().getPort());
        running = true;

        int boundPort = serverSocket.getLocalPort();
        log.info("Custom RFC 6455 WebSocket server listening on port={} node={}",
                boundPort, properties.getInstanceId());

        // Accept loop runs on its own virtual thread.
        virtualExecutor.submit(this::acceptLoop);

        // Periodic heartbeat Ping + dead half-open TCP socket reaper.
        int interval = Math.max(1, properties.getWs().getHeartbeatIntervalSeconds());
        heartbeatScheduler.scheduleAtFixedRate(
                this::runHeartbeatSweep, interval, interval, TimeUnit.SECONDS);
    }

    public int getBoundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                virtualExecutor.submit(() -> handleSocket(socket));
            } catch (SocketException e) {
                if (running) {
                    log.warn("WebSocket ServerSocket accept interrupted: {}", e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting WebSocket TCP connection", e);
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        WebSocketConnection connection = null;
        try {
            // Disable Nagle's algorithm: chat messages are small JSON frames; Nagle + TCP delayed
            // ACK can otherwise add 40-200ms artificial tail latency to every message.
            socket.setTcpNoDelay(true);
            // Enforce a tight timeout during the initial HTTP upgrade to defend against slowloris.
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();

            HttpUpgradeHandshake.UpgradeRequest req;
            String clientKey;
            try {
                req = HttpUpgradeHandshake.readUpgradeRequest(in);
                clientKey = HttpUpgradeHandshake.validateUpgradeHeaders(req);
            } catch (IllegalArgumentException e) {
                HttpUpgradeHandshake.writeHttpError(out, 400, "Bad Request", e.getMessage());
                socket.close();
                return;
            }

            Set<String> allowedOrigins = properties.getWs().parsedAllowedOrigins();
            if (!HttpUpgradeHandshake.isOriginAllowed(req, allowedOrigins)) {
                HttpUpgradeHandshake.writeHttpError(out, 403, "Forbidden", "Origin not allowed");
                socket.close();
                return;
            }

            String sessionToken = req.cookie(properties.getAuth().getSessionCookieName());
            Optional<UserSummary> authenticated = authService.resolveSession(sessionToken);
            if (authenticated.isEmpty()) {
                HttpUpgradeHandshake.writeHttpError(out, 401, "Unauthorized", "Valid session cookie required");
                socket.close();
                return;
            }
            // NOTE (MVP limitation): Session is checked once during handshake. If the user logs out
            // from another device, this already-open socket stays connected until closed. In
            // production we would broadcast a logout event via RabbitMQ to kick open sockets.

            String acceptKey = HttpUpgradeHandshake.computeAcceptKey(clientKey);
            HttpUpgradeHandshake.writeSwitchingProtocols(out, acceptKey);

            // Handshake succeeded: clear handshake timeout; liveness is now governed by Ping/Pong heartbeats.
            socket.setSoTimeout(0);

            UserSummary user = authenticated.get();
            connection = new WebSocketConnection(
                    user.id(),
                    user.username(),
                    socket,
                    out,
                    properties.getWs().getOutboundQueueCapacity());
            connection.startWriter(virtualExecutor);

            protocolHandler.onConnect(connection);
            readFrameLoop(in, connection);
        } catch (SocketTimeoutException e) {
            log.debug("WebSocket handshake timed out from {}", socket.getRemoteSocketAddress());
        } catch (WsProtocolException e) {
            log.debug("WebSocket protocol violation: {}", e.getMessage());
            if (connection != null) {
                connection.close(e.getCloseCode(), e.getMessage());
            }
        } catch (EOFException | SocketException e) {
            log.debug("WebSocket peer disconnected: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error on WebSocket connection", e);
            if (connection != null) {
                connection.close(WsOpCode.CLOSE_INTERNAL_ERROR, "Internal error");
            }
        } finally {
            if (connection != null) {
                connection.forceCloseSocket();
                protocolHandler.onDisconnect(connection);
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Reads RFC 6455 frames including fragmented messages (FIN=0 + CONTINUATION frames) and
     * control frames (PING/PONG/CLOSE) interleaved mid-message as required by RFC 6455 §5.4.
     */
    private void readFrameLoop(BufferedInputStream in, WebSocketConnection connection) throws IOException {
        int maxPayload = properties.getWs().getMaxPayloadBytes();
        ByteArrayOutputStream fragmentBuffer = null;
        WsOpCode fragmentedOpCode = null;

        while (connection.isOpen()) {
            WsFrame frame = WebSocketFrameCodec.readFrame(in, maxPayload, true);
            if (frame == null) {
                break;
            }
            connection.touchActivity();

            // Control frames may be injected anywhere in the middle of a fragmented message.
            if (frame.opCode().isControl()) {
                switch (frame.opCode()) {
                    case PING -> connection.sendPong(frame.payload());
                    case PONG -> protocolHandler.onHeartbeat(connection);
                    case CLOSE -> {
                        connection.close(WsOpCode.CLOSE_NORMAL, "Peer closed");
                        return;
                    }
                    default -> {
                    }
                }
                continue;
            }

            if (frame.opCode() == WsOpCode.CONTINUATION) {
                if (fragmentBuffer == null) {
                    throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Unexpected CONTINUATION frame");
                }
                if (fragmentBuffer.size() + frame.payload().length > maxPayload) {
                    throw new WsProtocolException(WsOpCode.CLOSE_MESSAGE_TOO_BIG, "Fragmented message exceeds limit");
                }
                fragmentBuffer.write(frame.payload());
                if (frame.fin()) {
                    byte[] complete = fragmentBuffer.toByteArray();
                    WsOpCode originalOp = fragmentedOpCode;
                    fragmentBuffer = null;
                    fragmentedOpCode = null;
                    dispatchDataMessage(connection, originalOp, complete);
                }
            } else {
                if (fragmentBuffer != null) {
                    throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Expected CONTINUATION frame");
                }
                if (frame.fin()) {
                    dispatchDataMessage(connection, frame.opCode(), frame.payload());
                } else {
                    fragmentedOpCode = frame.opCode();
                    fragmentBuffer = new ByteArrayOutputStream(frame.payload().length);
                    fragmentBuffer.write(frame.payload());
                }
            }
        }
    }

    private void dispatchDataMessage(WebSocketConnection connection, WsOpCode opCode, byte[] payload) {
        if (opCode != WsOpCode.TEXT) {
            throw new WsProtocolException(WsOpCode.CLOSE_UNSUPPORTED_DATA, "Only UTF-8 text JSON frames are supported");
        }
        String text = WebSocketFrameCodec.decodeUtf8Strict(payload);
        protocolHandler.onTextMessage(connection, text);
    }

    /**
     * Sends RFC 6455 Ping frames to all connected sockets and reaps connections that have not
     * responded within `heartbeatTimeoutSeconds`.
     *
     * WHY: On mobile networks (Wi-Fi -> 5G handoff, elevators, OS background suspension), TCP
     * connections frequently go "half-open" without ever delivering a FIN or RST packet. Without
     * server-driven Ping/Pong reaping, ghost sockets leak memory and show users permanently online.
     */
    private void runHeartbeatSweep() {
        try {
            long timeoutMs = properties.getWs().getHeartbeatTimeoutSeconds() * 1000L;
            protocolHandler.sweepHeartbeats(timeoutMs);
        } catch (Exception e) {
            log.warn("Heartbeat sweep error", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        heartbeatScheduler.shutdownNow();
        protocolHandler.closeAll(WsOpCode.CLOSE_GOING_AWAY, "Server shutting down");
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        virtualExecutor.shutdownNow();
        log.info("RawWebSocketServer stopped on node={}", properties.getInstanceId());
    }
}

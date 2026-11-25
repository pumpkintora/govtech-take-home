package com.chat.realtime.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single authenticated WebSocket connection with isolated outbound backpressure.
 *
 * WHY bounded outbound queue + dedicated writer virtual thread:
 * In a chat server serving mobile (3G/elevator) and desktop users simultaneously, a single slow
 * mobile receiver can have a full OS TCP send buffer. If the broker thread or sender thread wrote
 * directly to the receiver's Socket.getOutputStream(), that write would block and stall delivery
 * to ALL other users (and even other tabs of the same user).
 *
 * Instead, senders enqueue into a bounded ArrayBlockingQueue (O(1), non-blocking `offer`). If a
 * stalled mobile client fills its queue, we shed ONLY that slow connection with Close 1008; the
 * client automatically reconnects and catches up cleanly from PostgreSQL via `afterSeq` cursor sync.
 */
public class WebSocketConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);
    private static final WsFrame POISON_PILL = new WsFrame(true, WsOpCode.CLOSE, WsFrame.EMPTY);

    private final UUID connectionId;
    private final UUID userId;
    private final String username;
    private final Socket socket;
    private final OutputStream out;
    private final BlockingQueue<WsFrame> outboundQueue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());

    public WebSocketConnection(UUID userId, String username, Socket socket, OutputStream out, int queueCapacity) {
        this.connectionId = UUID.randomUUID();
        this.userId = userId;
        this.username = username;
        this.socket = socket;
        this.out = out;
        this.outboundQueue = new ArrayBlockingQueue<>(queueCapacity);
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isOpen() {
        return !closed.get() && !socket.isClosed();
    }

    public long getLastActivityMillis() {
        return lastActivityMillis.get();
    }

    public void touchActivity() {
        lastActivityMillis.set(System.currentTimeMillis());
    }

    public void startWriter(ExecutorService executor) {
        executor.submit(() -> {
            try {
                while (!closed.get()) {
                    WsFrame frame = outboundQueue.take();
                    if (frame == POISON_PILL) {
                        break;
                    }
                    WebSocketFrameCodec.writeFrame(out, frame);
                    if (frame.opCode() == WsOpCode.CLOSE) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.debug("Writer stream closed for user={} conn={}: {}", username, connectionId, e.getMessage());
            } finally {
                forceCloseSocket();
            }
        });
    }

    /**
     * Non-blocking enqueue of a UTF-8 JSON text frame.
     * Drops and closes ONLY this connection if its outbound queue overflows (slow consumer).
     */
    public boolean sendText(String text) {
        if (closed.get()) {
            return false;
        }
        WsFrame frame = WsFrame.text(text.getBytes(StandardCharsets.UTF_8));
        if (!outboundQueue.offer(frame)) {
            log.warn("Slow consumer detected for user={} conn={} (outbound queue full); disconnecting to force resync",
                    username, connectionId);
            close(WsOpCode.CLOSE_POLICY_VIOLATION, "Slow consumer");
            return false;
        }
        return true;
    }

    public boolean sendPing() {
        if (closed.get()) {
            return false;
        }
        byte[] payload = Long.toString(Instant.now().toEpochMilli()).getBytes(StandardCharsets.US_ASCII);
        return outboundQueue.offer(WsFrame.ping(payload));
    }

    public boolean sendPong(byte[] payload) {
        if (closed.get()) {
            return false;
        }
        return outboundQueue.offer(WsFrame.pong(payload));
    }

    public void close(int statusCode, String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Clear pending application messages so the Close frame is flushed promptly.
        outboundQueue.clear();
        if (!outboundQueue.offer(WsFrame.close(statusCode, reason))) {
            forceCloseSocket();
        }
    }

    public void forceCloseSocket() {
        closed.set(true);
        outboundQueue.offer(POISON_PILL);
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}

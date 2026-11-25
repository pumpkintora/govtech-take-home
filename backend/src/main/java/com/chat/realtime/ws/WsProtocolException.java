package com.chat.realtime.ws;

/**
 * Thrown when a peer violates RFC 6455 framing or payload limits.
 * Carries the exact RFC 6455 close status code so the server can send a compliant
 * Close frame before tearing down the underlying TCP socket.
 */
public class WsProtocolException extends RuntimeException {

    private final int closeCode;

    public WsProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }

    public int getCloseCode() {
        return closeCode;
    }
}

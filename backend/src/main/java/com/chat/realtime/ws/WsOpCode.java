package com.chat.realtime.ws;

/**
 * RFC 6455 Section 5.2 WebSocket opcodes and standard Section 7.4.1 close status codes.
 */
public enum WsOpCode {
    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_GOING_AWAY = 1001;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_UNSUPPORTED_DATA = 1003;
    public static final int CLOSE_INVALID_PAYLOAD = 1007;
    public static final int CLOSE_POLICY_VIOLATION = 1008;
    public static final int CLOSE_MESSAGE_TOO_BIG = 1009;
    public static final int CLOSE_INTERNAL_ERROR = 1011;

    private final int code;

    WsOpCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean isControl() {
        return (code & 0x08) != 0;
    }

    public static WsOpCode fromCode(int code) {
        return switch (code) {
            case 0x0 -> CONTINUATION;
            case 0x1 -> TEXT;
            case 0x2 -> BINARY;
            case 0x8 -> CLOSE;
            case 0x9 -> PING;
            case 0xA -> PONG;
            default -> throw new WsProtocolException(CLOSE_PROTOCOL_ERROR, "Unknown opcode: 0x" + Integer.toHexString(code));
        };
    }
}

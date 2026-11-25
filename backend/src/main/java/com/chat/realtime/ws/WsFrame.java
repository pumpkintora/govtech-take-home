package com.chat.realtime.ws;

/**
 * Represents a single decoded or outbound RFC 6455 WebSocket frame.
 */
public record WsFrame(boolean fin, WsOpCode opCode, byte[] payload) {

    public static final byte[] EMPTY = new byte[0];

    public static WsFrame text(byte[] utf8Payload) {
        return new WsFrame(true, WsOpCode.TEXT, utf8Payload);
    }

    public static WsFrame ping(byte[] payload) {
        return new WsFrame(true, WsOpCode.PING, payload == null ? EMPTY : payload);
    }

    public static WsFrame pong(byte[] payload) {
        return new WsFrame(true, WsOpCode.PONG, payload == null ? EMPTY : payload);
    }

    public static WsFrame close(int statusCode, String reason) {
        return new WsFrame(true, WsOpCode.CLOSE, WebSocketFrameCodec.buildClosePayload(statusCode, reason));
    }
}

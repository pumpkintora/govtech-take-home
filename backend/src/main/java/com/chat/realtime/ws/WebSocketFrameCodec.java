package com.chat.realtime.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Pure Java standard-library RFC 6455 Section 5 binary WebSocket frame encoder and decoder.
 *
 * Frame wire layout (RFC 6455 §5.2):
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 */
public final class WebSocketFrameCodec {

    private WebSocketFrameCodec() {
    }

    /**
     * Reads one complete WebSocket frame from the TCP stream.
     * Returns null on a clean EOF before the first header byte.
     */
    public static WsFrame readFrame(InputStream in, int maxPayloadBytes, boolean requireMask) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = in.read();
        if (b1 == -1) {
            throw new EOFException("Truncated WebSocket frame header");
        }

        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 & 0x70);
        if (rsv != 0) {
            // We do not negotiate any extensions (e.g. permessage-deflate), so RFC 6455 §5.2
            // mandates failing the connection if any RSV bit is non-zero.
            throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Non-zero RSV bits without extension");
        }

        WsOpCode opCode = WsOpCode.fromCode(b0 & 0x0F);
        boolean masked = (b1 & 0x80) != 0;
        if (requireMask && !masked) {
            // RFC 6455 §5.1: All client-to-server frames MUST be masked to mitigate proxy cache poisoning.
            throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Client frame must be masked");
        }

        long payloadLength = b1 & 0x7F;
        if (payloadLength == 126) {
            byte[] ext = readExact(in, 2);
            payloadLength = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
        } else if (payloadLength == 127) {
            byte[] ext = readExact(in, 8);
            // RFC 6455 §5.2: The most significant bit of a 64-bit length MUST be 0.
            if ((ext[0] & 0x80) != 0) {
                throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Invalid 64-bit length MSB");
            }
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | (ext[i] & 0xFFL);
            }
        }

        if (opCode.isControl()) {
            // RFC 6455 §5.5: Control frames (Close/Ping/Pong) MUST NOT be fragmented and
            // MUST have a payload length of 125 bytes or less.
            if (!fin) {
                throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Fragmented control frame");
            }
            if (payloadLength > 125) {
                throw new WsProtocolException(WsOpCode.CLOSE_PROTOCOL_ERROR, "Control frame payload > 125 bytes");
            }
        }

        if (payloadLength > maxPayloadBytes) {
            // Reject BEFORE allocating the byte[] so a malicious client sending a crafted
            // 64-bit length header cannot trigger an OutOfMemoryError.
            throw new WsProtocolException(WsOpCode.CLOSE_MESSAGE_TOO_BIG,
                    "Frame payload exceeds limit of " + maxPayloadBytes + " bytes");
        }

        byte[] maskingKey = masked ? readExact(in, 4) : null;
        byte[] payload = payloadLength == 0 ? WsFrame.EMPTY : readExact(in, (int) payloadLength);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskingKey[i & 3]);
            }
        }

        return new WsFrame(fin, opCode, payload);
    }

    /**
     * Encodes and flushes one server-to-client frame.
     * Server frames are NEVER masked per RFC 6455 Section 5.1.
     */
    public static void writeFrame(OutputStream out, WsFrame frame) throws IOException {
        byte[] payload = frame.payload() == null ? WsFrame.EMPTY : frame.payload();
        int len = payload.length;

        // Allocate a single contiguous header+payload buffer for small/medium frames to avoid
        // splitting a logical WebSocket frame across multiple tiny TCP write() syscalls.
        int headerSize = (len <= 125) ? 2 : (len <= 65535 ? 4 : 10);
        byte[] wire = new byte[headerSize + len];

        wire[0] = (byte) ((frame.fin() ? 0x80 : 0x00) | (frame.opCode().code() & 0x0F));
        if (len <= 125) {
            wire[1] = (byte) len;
        } else if (len <= 65535) {
            wire[1] = 126;
            wire[2] = (byte) ((len >>> 8) & 0xFF);
            wire[3] = (byte) (len & 0xFF);
        } else {
            wire[1] = 127;
            long len64 = len;
            for (int i = 0; i < 8; i++) {
                wire[2 + i] = (byte) ((len64 >>> (56 - i * 8)) & 0xFF);
            }
        }

        if (len > 0) {
            System.arraycopy(payload, 0, wire, headerSize, len);
        }

        out.write(wire);
        out.flush();
    }

    public static byte[] buildClosePayload(int statusCode, String reason) {
        byte[] reasonBytes = (reason == null || reason.isEmpty())
                ? WsFrame.EMPTY
                : reason.getBytes(StandardCharsets.UTF_8);
        // Control frames are capped at 125 bytes total (2 bytes status + max 123 bytes reason).
        int reasonLen = Math.min(reasonBytes.length, 123);
        byte[] payload = new byte[2 + reasonLen];
        payload[0] = (byte) ((statusCode >>> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonLen);
        return payload;
    }

    public static String decodeUtf8Strict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WsProtocolException(WsOpCode.CLOSE_INVALID_PAYLOAD, "Invalid UTF-8 in text frame");
        }
    }

    /**
     * Loops until exactly `count` bytes are read from the TCP stream.
     *
     * WHY: TCP is an unframed byte stream, not message-oriented; a single InputStream.read()
     * commonly returns fewer bytes than requested when packets are fragmented over mobile/WAN links.
     */
    private static byte[] readExact(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(buf, offset, count - offset);
            if (n == -1) {
                throw new EOFException("Unexpected EOF while reading " + count + " bytes (got " + offset + ")");
            }
            offset += n;
        }
        return buf;
    }
}

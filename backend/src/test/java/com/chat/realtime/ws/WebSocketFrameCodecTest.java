package com.chat.realtime.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameCodecTest {

    @Test
    void decodesMaskedClientTextFrame7Bit() throws IOException {
        byte[] wire = encodeClientMaskedFrame(true, WsOpCode.TEXT, "Hello WebSocket!".getBytes(StandardCharsets.UTF_8));
        WsFrame frame = WebSocketFrameCodec.readFrame(new ByteArrayInputStream(wire), 65536, true);

        assertTrue(frame.fin());
        assertEquals(WsOpCode.TEXT, frame.opCode());
        assertEquals("Hello WebSocket!", WebSocketFrameCodec.decodeUtf8Strict(frame.payload()));
    }

    @Test
    void decodesAndEncodesExtended16BitPayloadLength() throws IOException {
        String largeText = "A".repeat(3000);
        byte[] payload = largeText.getBytes(StandardCharsets.UTF_8);

        byte[] clientWire = encodeClientMaskedFrame(true, WsOpCode.TEXT, payload);
        WsFrame decoded = WebSocketFrameCodec.readFrame(new ByteArrayInputStream(clientWire), 65536, true);
        assertEquals(largeText, WebSocketFrameCodec.decodeUtf8Strict(decoded.payload()));

        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();
        WebSocketFrameCodec.writeFrame(serverOut, WsFrame.text(payload));
        WsFrame roundTripped = WebSocketFrameCodec.readFrame(
                new ByteArrayInputStream(serverOut.toByteArray()), 65536, false);
        assertArrayEquals(payload, roundTripped.payload());
    }

    @Test
    void rejectsUnmaskedClientFramePerRfc6455() {
        byte[] unmasked = new byte[]{(byte) 0x81, 0x02, 'h', 'i'};
        WsProtocolException ex = assertThrows(WsProtocolException.class, () ->
                WebSocketFrameCodec.readFrame(new ByteArrayInputStream(unmasked), 65536, true));
        assertEquals(WsOpCode.CLOSE_PROTOCOL_ERROR, ex.getCloseCode());
    }

    @Test
    void rejectsOversizedPayloadBeforeAllocation() {
        // Masked frame declaring 16-bit length = 2000 bytes against a 512-byte limit.
        byte[] headerOnly = new byte[]{(byte) 0x81, (byte) (0x80 | 126), 0x07, (byte) 0xD0};
        WsProtocolException ex = assertThrows(WsProtocolException.class, () ->
                WebSocketFrameCodec.readFrame(new ByteArrayInputStream(headerOnly), 512, true));
        assertEquals(WsOpCode.CLOSE_MESSAGE_TOO_BIG, ex.getCloseCode());
    }

    @Test
    void rejectsFragmentedControlFrame() throws IOException {
        byte[] fragmentedPing = encodeClientMaskedFrame(false, WsOpCode.PING, new byte[]{1, 2});
        WsProtocolException ex = assertThrows(WsProtocolException.class, () ->
                WebSocketFrameCodec.readFrame(new ByteArrayInputStream(fragmentedPing), 65536, true));
        assertEquals(WsOpCode.CLOSE_PROTOCOL_ERROR, ex.getCloseCode());
    }

    @Test
    void rejectsInvalidUtf8TextPayload() {
        byte[] invalidUtf8 = new byte[]{(byte) 0xFF, (byte) 0xFE};
        WsProtocolException ex = assertThrows(WsProtocolException.class, () ->
                WebSocketFrameCodec.decodeUtf8Strict(invalidUtf8));
        assertEquals(WsOpCode.CLOSE_INVALID_PAYLOAD, ex.getCloseCode());
    }

    private static byte[] encodeClientMaskedFrame(boolean fin, WsOpCode opCode, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0x00) | opCode.code());
        int len = payload.length;
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) len >>> (i * 8)) & 0xFF));
            }
        }
        byte[] mask = new byte[]{0x37, (byte) 0xFA, 0x21, 0x3D};
        out.write(mask);
        for (int i = 0; i < len; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
        return out.toByteArray();
    }
}

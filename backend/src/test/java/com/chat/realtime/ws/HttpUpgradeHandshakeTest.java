package com.chat.realtime.ws;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUpgradeHandshakeTest {

    @Test
    void matchesOfficialRfc6455TestVector() {
        // RFC 6455 Section 1.3 canonical test vector
        String accept = HttpUpgradeHandshake.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept);
    }

    @Test
    void parsesUpgradeRequestCookiesAndPreservesPipelinedFrameBytes() throws IOException {
        String httpUpgrade = "GET /ws HTTP/1.1\r\n"
                + "Host: localhost:8080\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: http://localhost:8080\r\n"
                + "Cookie: theme=dark; CHAT_SESSION=secret-token-123\r\n"
                + "\r\n";

        // Simulate a client pipelining a WebSocket Ping frame immediately after \r\n\r\n
        byte[] pipelinedFrame = new byte[]{(byte) 0x89, (byte) 0x80, 0x01, 0x02, 0x03, 0x04};
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(httpUpgrade.getBytes(StandardCharsets.ISO_8859_1));
        combined.write(pipelinedFrame);

        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(combined.toByteArray()));
        HttpUpgradeHandshake.UpgradeRequest req = HttpUpgradeHandshake.readUpgradeRequest(in);

        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", HttpUpgradeHandshake.validateUpgradeHeaders(req));
        assertEquals("secret-token-123", req.cookie("CHAT_SESSION"));
        assertTrue(HttpUpgradeHandshake.isOriginAllowed(req, Set.of("http://localhost:8080")));
        assertFalse(HttpUpgradeHandshake.isOriginAllowed(
                new HttpUpgradeHandshake.UpgradeRequest("GET", "/ws",
                        java.util.Map.of("origin", "https://evil.example.com", "host", "localhost:8080")),
                Set.of("http://localhost:8080")));

        // Verify the pipelined WebSocket frame is still intact in the same BufferedInputStream
        WsFrame frame = WebSocketFrameCodec.readFrame(in, 1024, true);
        assertEquals(WsOpCode.PING, frame.opCode());
    }
}

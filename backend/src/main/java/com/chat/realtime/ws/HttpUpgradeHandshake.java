package com.chat.realtime.ws;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implements the RFC 6455 Section 4.2 server-side HTTP/1.1 WebSocket opening handshake
 * directly over a raw TCP stream.
 *
 * WHY raw stream parsing instead of a servlet/library:
 * 1. Satisfies building the core real-time transport one level deeper on standard library primitives.
 * 2. Lets us authenticate the HttpOnly session cookie AND enforce Origin validation (CSWSH
 *    defense) BEFORE promoting the socket to a long-lived WebSocket connection, so unauthenticated
 *    clients never consume connection registry slots or writer threads.
 * 3. Reads directly from a shared BufferedInputStream (up to \r\n\r\n) so if a client pipelines
 *    the first WebSocket frame in the same TCP packet as the HTTP upgrade request, those bytes
 *    remain intact in the stream for WebSocketFrameCodec.
 */
public final class HttpUpgradeHandshake {

    // Fixed GUID mandated by RFC 6455 Section 1.3 for Sec-WebSocket-Accept derivation.
    public static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private HttpUpgradeHandshake() {
    }

    public record UpgradeRequest(String method, String path, Map<String, String> headers) {
        public String header(String lowerCaseName) {
            return headers.get(lowerCaseName.toLowerCase(Locale.ROOT));
        }

        public String cookie(String cookieName) {
            String raw = header("cookie");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String name = pair.substring(0, eq).trim();
                    if (name.equals(cookieName)) {
                        return pair.substring(eq + 1).trim();
                    }
                }
            }
            return null;
        }
    }

    public static UpgradeRequest readUpgradeRequest(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(512);
        int state = 0; // Matches \r\n\r\n state machine

        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Client closed connection during HTTP upgrade handshake");
            }
            headerBuf.write(b);
            if (headerBuf.size() > MAX_HEADER_BYTES) {
                // Hard cap prevents Slowloris / unbounded header memory exhaustion.
                throw new IllegalArgumentException("HTTP upgrade headers exceed " + MAX_HEADER_BYTES + " bytes");
            }

            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    break;
                }
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }

        String raw = headerBuf.toString(StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\r\n");
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty HTTP request");
        }

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 3) {
            throw new IllegalArgumentException("Malformed HTTP request line");
        }
        String method = requestLine[0];
        String path = requestLine[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            // Per RFC 7230, duplicate headers (e.g. multiple Cookie headers) may be merged.
            headers.merge(name, value, (existing, incoming) ->
                    name.equals("cookie") ? existing + "; " + incoming : existing + ", " + incoming);
        }

        return new UpgradeRequest(method, path, headers);
    }

    /**
     * Validates RFC 6455 Section 4.2.1 requirements and returns the Sec-WebSocket-Key.
     */
    public static String validateUpgradeHeaders(UpgradeRequest req) {
        if (!"GET".equalsIgnoreCase(req.method())) {
            throw new IllegalArgumentException("WebSocket upgrade must use GET");
        }
        String upgrade = req.header("upgrade");
        if (upgrade == null || !upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
            throw new IllegalArgumentException("Missing or invalid Upgrade: websocket header");
        }
        String connection = req.header("connection");
        if (connection == null || !connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
            throw new IllegalArgumentException("Missing or invalid Connection: Upgrade header");
        }
        String version = req.header("sec-websocket-version");
        if (!"13".equals(version)) {
            throw new IllegalArgumentException("Unsupported Sec-WebSocket-Version (expected 13)");
        }
        String key = req.header("sec-websocket-key");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Missing Sec-WebSocket-Key header");
        }
        // RFC 6455 requires a base64-encoded 16-byte nonce.
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length != 16) {
                throw new IllegalArgumentException("Sec-WebSocket-Key must decode to 16 bytes");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 in Sec-WebSocket-Key", e);
        }
        return key;
    }

    /**
     * Cross-Site WebSocket Hijacking (CSWSH) protection.
     *
     * WHY: Unlike XHR/fetch, browser WebSocket upgrades are NOT subject to CORS preflight, yet
     * browsers still attach SameSite=Lax/None cookies on top-level/same-site requests. Verifying
     * the Origin header prevents malicious third-party sites from opening authenticated WebSocket
     * connections using the victim's session cookie.
     */
    public static boolean isOriginAllowed(UpgradeRequest req, Set<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return true;
        }
        String origin = req.header("origin");
        // Non-browser clients (CLI tests, native mobile apps) may omit the Origin header.
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (allowedOrigins.contains(origin)) {
            return true;
        }
        // Also allow exact same-origin match against Host header for flexible local ports/hosts.
        String host = req.header("host");
        if (host != null && (origin.equals("http://" + host) || origin.equals("https://" + host))) {
            return true;
        }
        return false;
    }

    /**
     * Computes Sec-WebSocket-Accept = Base64(SHA-1(clientKey + RFC_6455_GUID)).
     */
    public static String computeAcceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm unavailable", e);
        }
    }

    public static void writeSwitchingProtocols(OutputStream out, String acceptKey) throws IOException {
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    public static void writeHttpError(OutputStream out, int status, String statusText, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bodyBytes);
        out.flush();
    }
}

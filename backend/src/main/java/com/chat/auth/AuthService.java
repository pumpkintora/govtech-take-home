package com.chat.auth;

import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_.-]{3,32}$");

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public AuthService(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record AuthResult(UserSummary user, String sessionToken) {
    }

    public AuthResult register(String rawUsername, String rawDisplayName, String password) {
        String username = normalizeUsername(rawUsername);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username must be 3-32 characters (lowercase letters, digits, _, ., -)");
        }
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be between 6 and 128 characters");
        }
        String displayName = (rawDisplayName == null || rawDisplayName.isBlank())
                ? username
                : rawDisplayName.trim();
        if (displayName.length() > 80) {
            displayName = displayName.substring(0, 80);
        }

        UUID userId = UUID.randomUUID();
        String passwordHash = PasswordHasher.hashPassword(password);
        try {
            jdbc.update(
                    "INSERT INTO users (id, username, display_name, password_hash) VALUES (?, ?, ?, ?)",
                    userId, username, displayName, passwordHash);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }

        String token = createSession(userId);
        return new AuthResult(new UserSummary(userId, username, displayName, false), token);
    }

    public AuthResult login(String rawUsername, String password) {
        String username = normalizeUsername(rawUsername);
        List<UserAuthRow> rows = jdbc.query(
                "SELECT id, username, display_name, password_hash FROM users WHERE username = ?",
                (rs, i) -> new UserAuthRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("password_hash")),
                username);

        if (rows.isEmpty() || !PasswordHasher.verifyPassword(password, rows.getFirst().passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        UserAuthRow user = rows.getFirst();
        String token = createSession(user.id());
        return new AuthResult(new UserSummary(user.id(), user.username(), user.displayName(), false), token);
    }

    public Optional<UserSummary> resolveSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = PasswordHasher.sha256Hex(sessionToken);
        List<UserSummary> rows = jdbc.query("""
                SELECT u.id, u.username, u.display_name
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > NOW()
                """,
                (rs, i) -> new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        false),
                tokenHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void logout(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return;
        }
        String tokenHash = PasswordHasher.sha256Hex(sessionToken);
        jdbc.update("DELETE FROM sessions WHERE token_hash = ?", tokenHash);
    }

    public void seedUserIfMissing(String username, String displayName, String password) {
        String normalized = normalizeUsername(username);
        String passwordHash = PasswordHasher.hashPassword(password);
        jdbc.update("""
                INSERT INTO users (id, username, display_name, password_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (username) DO NOTHING
                """,
                UUID.randomUUID(), normalized, displayName, passwordHash);
    }

    private String createSession(UUID userId) {
        // NOTE (MVP limitation): Expired sessions are ignored on read, but a production system
        // should run a scheduled cleanup job (or store sessions in Redis with TTL) so old rows
        // don't accumulate in PostgreSQL over time.
        String token = PasswordHasher.generateSessionToken();
        String tokenHash = PasswordHasher.sha256Hex(token);
        Instant expiresAt = Instant.now().plus(properties.getAuth().getSessionTtlHours(), ChronoUnit.HOURS);
        jdbc.update(
                "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, userId, Timestamp.from(expiresAt));
        return token;
    }

    private String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private record UserAuthRow(UUID id, String username, String displayName, String passwordHash) {
    }
}

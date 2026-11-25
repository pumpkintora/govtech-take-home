package com.chat.auth;

import com.chat.auth.AuthService.AuthResult;
import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AppProperties properties;

    public AuthController(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    public record RegisterRequest(String username, String displayName, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/register")
    public UserSummary register(@RequestBody RegisterRequest req, HttpServletResponse response) {
        AuthResult result = authService.register(req.username(), req.displayName(), req.password());
        attachSessionCookie(response, result.sessionToken());
        return result.user();
    }

    @PostMapping("/login")
    public UserSummary login(@RequestBody LoginRequest req, HttpServletResponse response) {
        AuthResult result = authService.login(req.username(), req.password());
        attachSessionCookie(response, result.sessionToken());
        return result.user();
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = SessionAuthFilter.extractCookie(request, properties.getAuth().getSessionCookieName());
        authService.logout(token);
        clearSessionCookie(response);
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UserSummary user = (UserSummary) request.getAttribute(SessionAuthFilter.AUTH_USER_ATTR);
        return Map.of(
                "user", user,
                "nodeId", properties.getInstanceId());
    }

    private void attachSessionCookie(HttpServletResponse response, String token) {
        // HttpOnly prevents XSS scripts from reading the session token; SameSite=Lax defends
        // against CSRF while allowing normal top-level navigation and same-origin WS upgrades.
        ResponseCookie cookie = ResponseCookie.from(properties.getAuth().getSessionCookieName(), token)
                .httpOnly(true)
                .secure(properties.getAuth().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofHours(properties.getAuth().getSessionTtlHours()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.getAuth().getSessionCookieName(), "")
                .httpOnly(true)
                .secure(properties.getAuth().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

package com.chat.auth;

import com.chat.chat.ChatModels.UserSummary;
import com.chat.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Extracts and validates the HttpOnly session cookie on protected REST API routes.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_ATTR = "chat.auth.user";
    public static final String AUTH_TOKEN_ATTR = "chat.auth.token";

    private final AuthService authService;
    private final AppProperties properties;

    public SessionAuthFilter(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!path.startsWith("/api/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/logout")
                || path.equals("/api/health")
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractCookie(request, properties.getAuth().getSessionCookieName());
        Optional<UserSummary> user = authService.resolveSession(token);
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        request.setAttribute(AUTH_USER_ATTR, user.get());
        request.setAttribute(AUTH_TOKEN_ATTR, token);
        filterChain.doFilter(request, response);
    }

    public static String extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

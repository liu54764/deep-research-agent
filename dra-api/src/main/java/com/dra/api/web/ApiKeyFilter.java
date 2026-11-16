package com.dra.api.web;

import com.dra.api.config.ApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiProperties properties;

    public ApiKeyFilter(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Static test UI is public; only /api/** requires the key. EventSource still uses
        // ?api_key= because browsers cannot set headers on the SSE handshake.
        return uri.startsWith("/actuator/health") || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader("X-API-Key");
        if (provided == null || provided.isBlank()) {
            // EventSource cannot set headers in a browser; allow query token over TLS.
            provided = request.getParameter("api_key");
        }
        byte[] expected = properties.getApiKey().getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, actual)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write(
                "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"missing or invalid API key\"}");
    }
}

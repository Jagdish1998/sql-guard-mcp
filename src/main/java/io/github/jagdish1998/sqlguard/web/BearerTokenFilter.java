package io.github.jagdish1998.sqlguard.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import io.github.jagdish1998.sqlguard.config.HttpAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a bearer token on the MCP endpoint.
 *
 * <p>Spring AI's HTTP transports are authentication-agnostic by design: the
 * auto-configuration publishes the JSON-RPC route but enforces nothing. Left alone, anyone
 * who can reach the port can enumerate and invoke every registered tool. For a server whose
 * tools read a database, that is the whole security boundary, so it has to be added here.
 *
 * <p>Health endpoints are left open so a platform load balancer can probe the service
 * without holding the token.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public BearerTokenFilter(HttpAuthProperties properties) {
        this.expectedToken = properties.getAuthToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health") || path.equals("/actuator/info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(request, response, "missing bearer token");
            return;
        }

        byte[] presented = header.substring(BEARER_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);

        // Constant-time comparison. A token check that returns early on the first wrong
        // byte leaks the token's prefix to anyone willing to measure.
        if (!MessageDigest.isEqual(presented, this.expectedToken)) {
            reject(request, response, "invalid bearer token");
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.warn("sqlguard rejected an unauthenticated request to {} from {}: {}",
                request.getRequestURI(), request.getRemoteAddr(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        // No detail in the body: a caller without the token gets nothing to work with.
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}

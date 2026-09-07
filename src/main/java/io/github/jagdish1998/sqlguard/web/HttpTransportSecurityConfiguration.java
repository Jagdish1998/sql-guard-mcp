package io.github.jagdish1998.sqlguard.web;

import io.github.jagdish1998.sqlguard.config.HttpAuthProperties;
import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the bearer-token filter, and refuses to start an unprotected HTTP server.
 *
 * <p>Failing at startup is the point. An MCP server exposing database tools over an
 * unauthenticated port is not a service with a missing feature, it is an open door, and the
 * failure mode of "forgot to set the token" has to be louder than a log line nobody reads.
 * Opting out is possible but has to be written down explicitly.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HttpAuthProperties.class)
public class HttpTransportSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HttpTransportSecurityConfiguration.class);

    private static final int MINIMUM_SANE_TOKEN_LENGTH = 24;

    @Bean
    public FilterRegistrationBean<Filter> mcpBearerTokenFilter(HttpAuthProperties properties) {
        String token = properties.getAuthToken();
        boolean hasToken = token != null && !token.isBlank();

        if (!hasToken) {
            if (!properties.isAllowUnauthenticated()) {
                throw new IllegalStateException("""
                        Refusing to start: the HTTP transport is enabled but no bearer token is set.

                        This server exposes tools that read your database. Without a token, anyone who can \
                        reach this port can list and call them.

                        Set SQLGUARD_HTTP_AUTH_TOKEN to a strong random value, or, only for a local \
                        development run bound to localhost, set sqlguard.http.allow-unauthenticated=true.

                        In STDIO mode this does not apply, because there is no listener to protect.""");
            }

            log.warn("sqlguard is serving MCP over HTTP with authentication DISABLED because "
                    + "sqlguard.http.allow-unauthenticated=true. Do not expose this beyond localhost.");

            // A registration still needs a filter instance even when disabled, because
            // Spring Boot asks it to describe itself before it checks the enabled flag.
            FilterRegistrationBean<Filter> disabled =
                    new FilterRegistrationBean<>((request, response, chain) -> chain.doFilter(request, response));
            disabled.setEnabled(false);
            return disabled;
        }

        if (token.length() < MINIMUM_SANE_TOKEN_LENGTH) {
            log.warn("sqlguard bearer token is shorter than {} characters. Prefer a long random value.",
                    MINIMUM_SANE_TOKEN_LENGTH);
        }

        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new BearerTokenFilter(properties));
        registration.addUrlPatterns("/*");
        // Ahead of the MCP handler, so an unauthenticated request never reaches a tool.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        log.info("sqlguard MCP HTTP transport is protected by a bearer token");
        return registration;
    }
}

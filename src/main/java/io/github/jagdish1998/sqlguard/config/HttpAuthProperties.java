package io.github.jagdish1998.sqlguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bearer-token settings for the HTTP transport.
 *
 * <p>Only relevant when the server runs as a network service. In STDIO mode there is no
 * listener to protect: the client starts the server as a child process and talks to it over
 * pipes.
 */
@ConfigurationProperties(prefix = "sqlguard.http")
public class HttpAuthProperties {

    /**
     * Shared secret clients must present as {@code Authorization: Bearer <token>}.
     *
     * <p>Supply it through an environment variable. A token in a config file ends up in
     * version control.
     */
    private String authToken;

    /**
     * Permits running the HTTP transport with no token at all.
     *
     * <p>Off by default, and the server refuses to start rather than quietly exposing an
     * unauthenticated route into a database. Reasonable for a bound-to-localhost
     * development run, never for a deployed one.
     */
    private boolean allowUnauthenticated = false;

    public String getAuthToken() {
        return this.authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public boolean isAllowUnauthenticated() {
        return this.allowUnauthenticated;
    }

    public void setAllowUnauthenticated(boolean allowUnauthenticated) {
        this.allowUnauthenticated = allowUnauthenticated;
    }
}

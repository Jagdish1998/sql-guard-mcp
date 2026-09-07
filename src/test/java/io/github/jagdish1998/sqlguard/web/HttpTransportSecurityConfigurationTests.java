package io.github.jagdish1998.sqlguard.web;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the server will not quietly expose an unauthenticated database gateway.
 *
 * <p>This is the test worth having in this class. Spring AI's HTTP transports enforce no
 * authentication of their own, so "we forgot to configure a token" has to be a startup
 * failure rather than a warning in a log nobody tails.
 */
class HttpTransportSecurityConfigurationTests {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(HttpTransportSecurityConfiguration.class);

    @Test
    @DisplayName("refuses to start when the HTTP transport has no token")
    void failsFastWithoutAToken() {
        this.contextRunner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("SQLGUARD_HTTP_AUTH_TOKEN"));
    }

    @Test
    void registersTheFilterWhenATokenIsPresent() {
        this.contextRunner
                .withPropertyValues("sqlguard.http.auth-token=a-sufficiently-long-test-token-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.isEnabled()).isTrue();
                    assertThat(registration.getFilter()).isInstanceOf(BearerTokenFilter.class);
                });
    }

    @Test
    @DisplayName("running without authentication is possible, but only when asked for explicitly")
    void allowsOptingOutDeliberately() {
        this.contextRunner
                .withPropertyValues("sqlguard.http.allow-unauthenticated=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<Filter> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("a blank token counts as no token")
    void treatsABlankTokenAsMissing() {
        this.contextRunner
                .withPropertyValues("sqlguard.http.auth-token=   ")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }
}

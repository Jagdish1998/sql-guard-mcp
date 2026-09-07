package io.github.jagdish1998.sqlguard.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import io.github.jagdish1998.sqlguard.dialect.GenericDialect;
import io.github.jagdish1998.sqlguard.dialect.PostgresDialect;
import io.github.jagdish1998.sqlguard.dialect.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the dialect by asking the driver what it is connected to.
 *
 * <p>Detection happens once at startup rather than per query. If the probe fails the
 * server still starts on the generic dialect: losing cost estimation degrades the service,
 * whereas refusing to boot removes it entirely, and the guardrails that matter most do not
 * depend on knowing the vendor.
 */
@Configuration(proxyBeanMethods = false)
public class DialectConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DialectConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SqlDialect.class)
    public SqlDialect sqlDialect(DataSource dataSource) {
        String product = probeProduct(dataSource);

        if (product != null && product.toLowerCase(Locale.ROOT).contains("postgres")) {
            log.info("sqlguard detected PostgreSQL; cost estimation via EXPLAIN (FORMAT JSON) is enabled");
            return new PostgresDialect();
        }

        log.warn("sqlguard detected '{}', which has no dedicated dialect. Query cost estimation is "
                + "disabled; row limits, the statement timeout and read-only execution still apply.",
                (product != null) ? product : "an unknown database");
        return new GenericDialect(product);
    }

    private String probeProduct(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        }
        catch (SQLException ex) {
            log.warn("sqlguard could not identify the database at startup: {}", ex.getMessage());
            return null;
        }
    }
}

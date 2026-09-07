package io.github.jagdish1998.sqlguard;

import io.github.jagdish1998.sqlguard.config.SqlGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * An MCP server that lets a language model read a relational database without being able
 * to damage it.
 *
 * <p>Runs in one of two transports, chosen by profile:
 * <ul>
 *   <li>STDIO, as a child process of an MCP client such as Claude Desktop or Kiro. This is
 *       the default and needs no network listener at all.</li>
 *   <li>Streamable HTTP, for a hosted deployment, guarded by a bearer token.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(SqlGuardProperties.class)
public class SqlGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlGuardApplication.class, args);
    }
}

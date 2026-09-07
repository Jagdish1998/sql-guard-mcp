# sql-guard-mcp

[![CI](https://github.com/Jagdish1998/sql-guard-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/Jagdish1998/sql-guard-mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F.svg)](https://spring.io/projects/spring-boot)

An [MCP](https://modelcontextprotocol.io) server that lets a language model query a
relational database without being able to damage it.

Point it at PostgreSQL, add it to your MCP client, and the model can explore the schema and
run `SELECT` statements. It cannot write, cannot read database internals, cannot lock rows,
cannot run a query the planner says is too expensive, and cannot see the contents of columns
that hold credentials or personal data.

---

## Why

Handing an LLM a database connection is the obvious way to let it answer questions about your
data, and it is also how you end up with `DELETE FROM orders` in production. The usual
mitigations are weaker than they look:

- **"I told it to only read."** A prompt is a preference, not a permission.
- **"I check the SQL starts with SELECT."** `WITH gone AS (DELETE FROM orders RETURNING *)
  SELECT * FROM gone` starts with `WITH`, is a `SELECT` at its top level, and deletes every
  row.
- **"I block keywords."** `pg_sl/**/eep(10)` is not `pg_sleep(10)` to a string matcher, and
  is exactly that to PostgreSQL.

This server parses the SQL with a real grammar, walks the parse tree for writes hiding inside
reads, asks the planner what a query will cost before running it, and then executes what
survives inside a read-only transaction on a read-only role. The layers are ordered
deliberately: the parser exists to give the model an error it can act on, and the database
permissions exist to make a write impossible.

---

## Install

### With an MCP client

Build the jar, then register it. Works with Claude Desktop, Kiro, Cursor, or anything else
that speaks MCP over STDIO.

```bash
git clone https://github.com/Jagdish1998/sql-guard-mcp.git
cd sql-guard-mcp
./mvnw -DskipTests package
```

Add to your client's `mcp.json`:

```json
{
  "mcpServers": {
    "sql-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/sql-guard-mcp/target/sql-guard-mcp-0.1.0-SNAPSHOT.jar"],
      "env": {
        "SQLGUARD_DB_URL": "jdbc:postgresql://localhost:5432/your_database",
        "SQLGUARD_DB_USER": "sqlguard_readonly",
        "SQLGUARD_DB_PASSWORD": "your_password"
      }
    }
  }
}
```

That is the whole install. STDIO needs no port and no network listener: the client starts the
server as a child process and talks to it over pipes.

### Create the database role first

The server assumes it is connecting as a role that cannot write. Do not skip this and connect
as a superuser; the application's checks are a usability layer, not the security boundary.

```sql
CREATE ROLE sqlguard_readonly WITH LOGIN PASSWORD 'change_me';
GRANT CONNECT ON DATABASE your_database TO sqlguard_readonly;
GRANT USAGE ON SCHEMA public TO sqlguard_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sqlguard_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO sqlguard_readonly;
ALTER ROLE sqlguard_readonly SET default_transaction_read_only = on;
```

[`docker/initdb/03-readonly-role.sql`](docker/initdb/03-readonly-role.sql) is a fuller
version with comments on what is withheld and why.

---

## Tools

| Tool | What it does |
| --- | --- |
| `list_tables` | Every table and view the server is permitted to read. The starting point when the model does not know the schema. |
| `describe_table` | Columns, types, nullability, primary key. Flags which columns will arrive masked, so the model does not build a query around a value it cannot see. |
| `explain_query` | Vets a statement against policy and returns the planner's cost estimate, without running it or returning data. |
| `run_query` | Runs one read-only `SELECT` and returns rows. |
| `recent_activity` | The audit trail: recent decisions, allowed and refused. |

Every tool is advertised with `readOnlyHint: true`, so clients that ask for confirmation
before destructive calls know these are safe.

---

## What it looks like

Real responses, captured from [`smoke-stdio.ps1`](smoke-stdio.ps1).

A normal query, capped and reported honestly:

```json
{
  "effectiveSql": "SELECT id, status FROM orders ORDER BY id LIMIT 3",
  "columns": ["id", "status"],
  "rows": [
    { "id": 1, "status": "SHIPPED" },
    { "id": 2, "status": "SHIPPED" },
    { "id": 3, "status": "PENDING" }
  ],
  "rowCount": 3,
  "truncated": true,
  "appliedLimit": 3,
  "redactedColumns": [],
  "elapsedMillis": 49
}
```

`truncated` and `appliedLimit` are there so the model never mistakes a clipped result for a
complete one and tell you a customer has three orders when they have thirty.

Sensitive columns come back masked, and the masking is declared:

```json
{
  "effectiveSql": "SELECT name, email FROM customers ORDER BY id LIMIT 3",
  "rows": [
    { "name": "Alice Kumar", "email": "a***@example.com" },
    { "name": "Bob Mensah",  "email": "b***@example.com" },
    { "name": "Carol Silva", "email": "c***@example.com" }
  ],
  "redactedColumns": ["email"]
}
```

The domain survives so grouping by it still works. A refusal explains the rule rather than
just saying no:

```
Query refused [NOT_READ_ONLY]. Only SELECT statements are allowed. This server cannot
run INSERT, UPDATE, DELETE, DDL, or any other write. Detail: statement type was Delete
```

That is deliberate. A bare "denied" invites the model to retry the same query in a slightly
different shape; a stated rule lets it correct course on the next turn.

---

## What it refuses

| Attempt | Refusal |
| --- | --- |
| `DELETE FROM orders` | `NOT_READ_ONLY` |
| `WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone` | `DATA_MODIFYING_CTE` |
| `SELECT 1; DROP TABLE orders` | `MULTIPLE_STATEMENTS` |
| `SELECT * INTO copy FROM orders` | `SELECT_INTO` |
| `SELECT * FROM orders FOR UPDATE` | `LOCKING_CLAUSE` |
| `SELECT pg_sleep(10)` | `BLOCKED_FUNCTION` |
| `SELECT pg_read_file('/etc/passwd')` | `BLOCKED_FUNCTION` |
| `SELECT pg_sleep/**/(10)` | `BLOCKED_FUNCTION` |
| `SELECT * FROM information_schema.tables` | `SYSTEM_CATALOG` |
| `SELECT * FROM private.secrets` | `SCHEMA_NOT_ALLOWED` |
| `SELECT * FROM employee_salaries` | `BLOCKED_TABLE` |
| a plan the planner prices above budget | `COST_LIMIT_EXCEEDED` |
| `GRANT ALL ON orders TO PUBLIC` | `PARSE_FAILED` |

That last row is the important one. JSqlParser has no grammar for `GRANT`, so it does not
parse, so it is refused. Anything the parser cannot read is denied by default, which means a
gap in the grammar can never become a way in.

---

## How it works

```
run_query(sql)
      |
      v
  ReadOnlySqlValidator ......... parse with a real grammar; reject non-SELECT,
      |                          statement stacking, writing CTEs, SELECT INTO,
      |                          locking clauses, blocked functions, system
      |                          catalogs, blocked tables, disallowed schemas
      v
  RowLimitRewriter ............. rewrite the parse tree so the query cannot
      |                          return more than max-rows
      v
  SqlDialect.estimate() ........ EXPLAIN (FORMAT JSON); refuse if the plan is
      |                          over budget, before any work is done
      v
  QueryExecutor ................ read-only connection, read-only transaction,
      |                          statement timeout, driver-level row cap,
      |                          rollback on the way out
      v
  PiiRedactor .................. mask sensitive columns by result-set label
      |
      v
  AuditLog ..................... record the decision either way
```

A few decisions worth explaining:

**Parsing, not pattern matching.** Text checks lose to comments, case, whitespace, and string
literals. Once the statement is a parse tree, "is this a SELECT" stops being a guess. The
checks that do run over text run over the statement *re-serialised from the tree*, so comments
are already gone and the text being scanned is the text the database will execute. This is why
`pg_sleep/**/(10)` is caught.

**Cost before execution, not a timeout.** A statement timeout only helps after the expensive
work has started. Asking the planner first means an over-budget query costs nothing. Where no
estimate is available the response says so rather than reporting zero, because zero reads as
"free".

**Clamping, not refusing, on row count.** A model that asks for a whole table usually wants to
look at the table. Returning the first 200 rows and saying so moves the conversation forward;
a refusal just costs a turn.

**Masking by result label, not by query text.** The model can write `SELECT * FROM users` or
`SELECT password AS notes`. Checking the label the driver reports covers both. Checking the
query text would be bypassed by an alias.

**The audit log is not in the audited database.** That database is read-only, so the server
could not write there anyway, and a trail an attacker can reach through the same tool is not a
trail. Entries go to a bounded in-memory ring plus SLF4J, for a real deployment to ship
somewhere durable.

---

## Configuration

Defaults are strict. Widening a limit should be a decision, not an oversight.

| Property | Default | Purpose |
| --- | --- | --- |
| `sqlguard.max-rows` | `200` | Row ceiling. Over-broad queries are clamped to it. |
| `sqlguard.query-timeout` | `5s` | Server-side statement timeout. |
| `sqlguard.max-estimated-rows` | `5000000` | Refuse plans estimating more rows. `-1` disables. |
| `sqlguard.max-estimated-cost` | `5000000` | Refuse plans costing more. `-1` disables. |
| `sqlguard.allowed-schemas` | `[public]` | Schema allow-list. Empty defers to the role's grants. |
| `sqlguard.blocked-tables` | `[]` | Tables to hide even where the role can read them. |
| `sqlguard.blocked-functions` | see [`SqlGuardProperties`](src/main/java/io/github/jagdish1998/sqlguard/config/SqlGuardProperties.java) | Filesystem, sleep and outbound-connection helpers. |
| `sqlguard.redaction.enabled` | `true` | Column masking on or off. |
| `sqlguard.redaction.rules` | see source | Ordered regex-to-strategy rules; first match wins. |

Masking strategies are `FULL`, `LAST_FOUR`, `EMAIL` and `HASH`. `HASH` is a stable SHA-256
pseudonym, so counting and grouping still work.

Environment variables: `SQLGUARD_DB_URL`, `SQLGUARD_DB_USER`, `SQLGUARD_DB_PASSWORD`,
`SQLGUARD_DB_POOL_SIZE`, `SQLGUARD_LOG_FILE`.

---

## Hosted mode

For a deployment rather than a local client, run the `http` profile to serve MCP over
Streamable HTTP.

```bash
docker build -t sql-guard-mcp .
docker run -p 8080:8080 \
  -e SQLGUARD_DB_URL=jdbc:postgresql://host:5432/db \
  -e SQLGUARD_DB_USER=sqlguard_readonly \
  -e SQLGUARD_DB_PASSWORD=... \
  -e SQLGUARD_HTTP_AUTH_TOKEN="$(openssl rand -hex 32)" \
  sql-guard-mcp
```

**The server refuses to start in HTTP mode without a bearer token.** Spring AI's HTTP
transports are authentication-agnostic by design: the auto-configuration publishes the
JSON-RPC endpoint and enforces nothing, so any client that can reach the port could otherwise
enumerate and call every tool. For a server whose tools read a database, that is the entire
security boundary, so a missing token is a startup failure rather than a warning in a log
nobody tails. `sqlguard.http.allow-unauthenticated=true` overrides it for a localhost
development run, and is never appropriate for a deployed one.

Token comparison is constant-time. `/actuator/health` stays open so a load balancer can probe
it.

One caveat if you deploy this to a scale-to-zero platform: a cold JVM takes tens of seconds to
answer the first request. Either keep a minimum instance warm or build a GraalVM native image,
for which Spring Boot already has AOT support.

---

## Local development

```bash
docker compose up -d          # PostgreSQL with demo data and a read-only role

SQLGUARD_DB_URL=jdbc:postgresql://localhost:5432/sqlguard_demo \
SQLGUARD_DB_USER=sqlguard_readonly \
SQLGUARD_DB_PASSWORD=readonly_password \
./mvnw spring-boot:run
```

The demo schema includes a `private` schema and an `employee_salaries` table specifically so
you can watch the allow-list and the blocklist exclude them by two independent mechanisms.

### Tests

```bash
./mvnw verify
```

134 tests, of which 125 run anywhere with no Docker daemon and no database. Integration tests
use H2 in PostgreSQL compatibility mode, and that trade-off is confined to one seam, the
`SqlDialect` interface. The remaining 9 skip locally and run in CI (see below).

The claims that need a real engine are covered separately, by the `postgres` job in CI:
that `EXPLAIN (FORMAT JSON)` yields usable numbers, and that a read-only transaction refuses a
write **handed straight to the executor with the validator bypassed**. That last test is the
one that earns the layered design its keep. It is guarded by
`@EnabledIfEnvironmentVariable(SQLGUARD_TEST_PG_URL)`, so it skips on a laptop with no
database and runs against a Postgres service container in CI.

`smoke-stdio.ps1` drives the real JSON-RPC protocol end to end: initialize, `tools/list`, then
`tools/call` for both a successful query and a refused one. Booting a Spring context proves
the beans wire up; only this proves an `mcp.json` install works.

---

## What this does not do

Being clear about the edges matters more than the feature list.

- **It does not detect sensitive data by looking at values.** Masking is decided from column
  names. An email address sitting in a free-text `notes` column is not caught. This is a
  guardrail against casual exposure of obviously sensitive columns, not a DLP engine.
- **`SELECT email AS contact` defeats the mask.** Redaction keys on the label the driver
  reports, and an alias changes it. Masking the underlying column instead would need full
  column-lineage analysis through arbitrary expressions and joins.
- **An unqualified table name cannot be schema-checked.** `SELECT * FROM orders` resolves
  through the connection's `search_path`, which belongs to the database. Only qualified names
  are checked against the allow-list; the role's grants are what constrain the rest.
- **The function blocklist is a blocklist.** It covers the known escape hatches, and a
  blocklist is never complete. The read-only role is what holds.
- **Cost estimates are estimates.** A planner working from stale statistics can be wrong in
  both directions.
- **Full WCAG-style assurance is not claimed anywhere** because there is no UI; this is a
  protocol server.
- **Only PostgreSQL has a dedicated dialect.** Anything else runs on `GenericDialect`, which
  reports no cost estimate rather than inventing one. Row caps, timeouts and read-only
  execution still apply.

---

## Built with

Java 21, Spring Boot 4.1.1, [Spring AI 2.0.1](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
MCP server starter, [JSqlParser 5.3](https://github.com/JSQLParser/JSqlParser), PostgreSQL,
H2 for tests.

## Licence

[MIT](LICENSE).

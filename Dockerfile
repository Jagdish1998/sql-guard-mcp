# Two stages: build with the JDK, ship on the JRE. Keeps Maven, the source and the local
# repository out of the runtime image.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not re-download the
# world on every build.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package && \
    mv target/sql-guard-mcp-*.jar target/app.jar


FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user, because this process talks to a database and needs nothing on the
# filesystem beyond its own jar.
RUN addgroup -S sqlguard && adduser -S -G sqlguard sqlguard

WORKDIR /app
COPY --from=build --chown=sqlguard:sqlguard /build/target/app.jar ./app.jar

USER sqlguard

# The container runs the HTTP transport: STDIO would need the client to own the process
# lifecycle, which is not what a container platform does. Startup therefore requires
# SQLGUARD_HTTP_AUTH_TOKEN, and the application refuses to boot without it.
ENV SPRING_PROFILES_ACTIVE=http \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"

# Cloud Run and most platforms inject PORT; 8080 is the fallback in application.yml.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:${PORT:-8080}/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

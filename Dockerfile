# ==============================================================================
# Build Stage
# ==============================================================================
FROM gradle:9.3.0-jdk21 AS builder

WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN GRADLE_USER_HOME=/gradle-cache gradle bootJar -x test --no-daemon

# ==============================================================================
# Runtime Stage (Optimized for 1 GB RAM VPS)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="TradingBot"

WORKDIR /app

# Install curl for healthcheck and tzdata for IST (Asia/Kolkata)
# hadolint ignore=DL3018
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Kolkata /etc/localtime && \
    echo "Asia/Kolkata" > /etc/timezone

# Create non-root system user (UID 10001) and allocate data and log directories
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup && \
    mkdir -p /app/data /app/logs && \
    chown -R 10001:10001 /app

# Copy executable jar from builder stage
COPY --from=builder --chown=10001:10001 /app/build/libs/*.jar /app/app.jar

USER 10001:10001

# Expose HTTP / Actuator port
EXPOSE 3000

# Environment and JVM settings for 1 GB VPS
ENV JAVA_OPTS="-XX:+UseSerialGC -Xms256m -Xmx384m -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=3000

VOLUME ["/app/data", "/app/logs"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD ["curl", "-f", "http://localhost:3000/actuator/health"]

STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
# ==============================================================================
# Build Stage
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Gradle wrapper and configuration files first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Make wrapper executable and download dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code and build application jar
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

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

# Create non-root system user (UID 10001) and allocate data directory for SQLite & sessions
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup && \
    mkdir -p /app/data && \
    chown -R 10001:10001 /app

# Copy executable jar from builder stage
COPY --from=builder --chown=10001:10001 /build/build/libs/*.jar /app/app.jar

USER 10001:10001

# Expose HTTP / Actuator port
EXPOSE 8080

# Environment and JVM settings for 1 GB VPS
ENV JAVA_OPTS="-XX:+UseSerialGC -Xms256m -Xmx384m -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=8080

VOLUME ["/app/data"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD ["curl", "-f", "http://localhost:8080/actuator/health"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

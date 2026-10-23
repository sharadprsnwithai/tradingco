# Stage 1: Build application jar
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy build configuration and wrapper
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY config ./config
COPY notifications/build.gradle.kts ./notifications/
COPY broker/build.gradle.kts ./broker/

# Copy module sources
COPY notifications/src ./notifications/src
COPY broker/src ./broker/src

# Run build and package boot jar
RUN chmod +x ./gradlew && ./gradlew :broker:bootJar --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

# Install curl for HEALTHCHECK per Ruling R9
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as non-root user
RUN useradd -u 1000 -m -s /bin/bash appuser \
    && chown -R appuser:appuser /app

USER appuser

COPY --from=builder --chown=appuser:appuser /app/broker/build/libs/*.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

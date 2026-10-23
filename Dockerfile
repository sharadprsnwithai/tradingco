# Minimal runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

# Install curl for HEALTHCHECK per Ruling R9
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as non-root user
RUN useradd -u 10001 -m -s /bin/bash appuser \
    && chown -R appuser:appuser /app

USER appuser

COPY --chown=appuser:appuser broker/build/libs/broker.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

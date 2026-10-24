# Minimal runtime image — alpine variant: musl JRE + busybox wget (healthcheck), no apt layer
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run as non-root user
RUN adduser -D -u 10001 appuser \
    && chown -R appuser:appuser /app

USER appuser

COPY --chown=appuser:appuser broker/build/libs/broker.jar /app/app.jar

EXPOSE 8080

# busybox wget replaces curl — R9 amended: no package install needed for HEALTHCHECK
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

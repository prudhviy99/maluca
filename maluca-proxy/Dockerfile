# Multi-stage build — build jar in a gradle image, copy into slim JRE image

FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace

# Copy only files needed for maluca-proxy module
COPY settings.gradle build.gradle ./
COPY maluca-proxy/build.gradle maluca-proxy/build.gradle
COPY maluca-proxy/src maluca-proxy/src

# Build without running tests (tests run in CI separately)
RUN gradle :maluca-proxy:bootJar --no-daemon -x test

# ── Runtime image ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/maluca-proxy/build/libs/*.jar app.jar

# Non-root user for security
RUN addgroup -S maluca && adduser -S maluca -G maluca
USER maluca

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

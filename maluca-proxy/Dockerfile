# ── Build stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY maluca-proxy/build.gradle maluca-proxy/build.gradle
COPY demo-backend/build.gradle demo-backend/build.gradle
# Warm the dependency cache before copying sources (better layer reuse)
RUN ./gradlew :maluca-proxy:dependencies --no-daemon -q > /dev/null 2>&1 || true

COPY maluca-proxy/src maluca-proxy/src
RUN ./gradlew :maluca-proxy:bootJar --no-daemon

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/maluca-proxy/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

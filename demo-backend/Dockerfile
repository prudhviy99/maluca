# ── Build stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY maluca-proxy/build.gradle maluca-proxy/build.gradle
COPY demo-backend/build.gradle demo-backend/build.gradle
RUN ./gradlew :demo-backend:dependencies --no-daemon -q > /dev/null 2>&1 || true

COPY demo-backend/src demo-backend/src
RUN ./gradlew :demo-backend:bootJar --no-daemon

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/demo-backend/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

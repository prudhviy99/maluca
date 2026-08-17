FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY maluca-proxy/build.gradle maluca-proxy/build.gradle
COPY demo-backend/build.gradle demo-backend/build.gradle
COPY maluca-contracts/build.gradle maluca-contracts/build.gradle
COPY maluca-triage/build.gradle maluca-triage/build.gradle
COPY maluca-mcp/build.gradle maluca-mcp/build.gradle
RUN ./gradlew :maluca-mcp:dependencies --no-daemon -q > /dev/null 2>&1 || true

COPY maluca-contracts/src maluca-contracts/src
COPY maluca-mcp/src maluca-mcp/src
RUN ./gradlew :maluca-mcp:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/maluca-mcp/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
    CMD curl --fail --silent http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

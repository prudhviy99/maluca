FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY demo-backend/build.gradle demo-backend/build.gradle
COPY demo-backend/src demo-backend/src

RUN gradle :demo-backend:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/demo-backend/build/libs/*.jar app.jar

RUN addgroup -S maluca && adduser -S maluca -G maluca
USER maluca

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

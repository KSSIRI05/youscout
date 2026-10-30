FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
COPY services services
RUN mvn -B -ntp -pl services/media-worker -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ffmpeg && addgroup -S youscout && adduser -S youscout -G youscout
WORKDIR /app
COPY --from=build --chown=youscout:youscout /src/services/media-worker/target/app.jar app.jar
USER youscout
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=12 CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]

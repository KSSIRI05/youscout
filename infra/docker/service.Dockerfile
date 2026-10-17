FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
COPY services services
ARG MODULE
RUN mvn -B -ntp -pl services/${MODULE} -am package -DskipTests
RUN cp services/${MODULE}/target/app.jar /app.jar
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S youscout && adduser -S youscout -G youscout
WORKDIR /app
COPY --from=build --chown=youscout:youscout /app.jar app.jar
USER youscout
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=12 CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "app.jar"]

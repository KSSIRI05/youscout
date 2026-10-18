# Étape de compilation : seuls le module demandé et ses dépendances Maven sont construits.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
COPY services services
ARG MODULE
# Les tests sont exécutés séparément par Maven en CI ; cette étape fabrique l’image exécutable.
RUN mvn -B -ntp -pl services/${MODULE} -am package -DskipTests
RUN cp services/${MODULE}/target/app.jar /app.jar
# Image finale : le JRE et le jar suffisent, sans outils de compilation ni sources.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S youscout && adduser -S youscout -G youscout
WORKDIR /app
COPY --from=build --chown=youscout:youscout /app.jar app.jar
# Le processus Java tourne avec un compte dédié, sans privilèges root.
USER youscout
EXPOSE 8080
# La sonde readiness inclut la base pour User et Skill ; Compose attend cette disponibilité.
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=12 CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "app.jar"]

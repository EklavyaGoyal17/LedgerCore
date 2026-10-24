FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=ledgercore-maven,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:24-jre-alpine@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f
RUN addgroup -S ledgercore && adduser -S ledgercore -G ledgercore
WORKDIR /app
COPY --from=build --chown=ledgercore:ledgercore /workspace/target/ledgercore-1.0.0.jar app.jar
USER ledgercore
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]

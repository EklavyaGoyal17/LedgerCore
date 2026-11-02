FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=ledgercore-maven,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
RUN addgroup -S ledgercore && adduser -S ledgercore -G ledgercore
WORKDIR /app
COPY --from=build --chown=ledgercore:ledgercore /workspace/target/ledgercore-1.0.0.jar app.jar
USER ledgercore
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]

# review-core image. Architecture §29.
#
# Build context is the REPOSITORY ROOT, not this directory, because the Maven reactor needs the root
# pom and the sibling modules this service depends on (§6). Compose sets `context: .` accordingly.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

# `-am` builds platform-contracts too; `-o` is deliberately absent so a clean builder can resolve.
# Tests are skipped HERE because the reactor's full suite runs in CI and in `./mvnw verify` — running
# them per-image would run the same tests three times across three builds.
RUN ./mvnw -pl services/review-core -am clean package -DskipTests

FROM eclipse-temurin:21-jre

# Non-root. A service that parses untrusted uploads and holds storage credentials should not be able
# to write outside its own directory.
RUN useradd --system --uid 10001 appuser
WORKDIR /app

COPY --from=build /workspace/services/review-core/target/*.jar /app/application.jar

USER appuser
EXPOSE 8080

# Container-aware heap sizing: without MaxRAMPercentage the JVM picks a default from the HOST's
# memory, then gets OOM-killed by the container limit it never saw.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]

# retrieval-comparison image. Architecture §29.
#
# Build context is the repository root (§6 reactor layout).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

# Retried on a TRANSIENT dependency-download failure, not on a build failure. This build pulls a
# few hundred artifacts, and a dropped connection to Maven Central failed the whole image — which
# in a four-image release means no digests are written and nothing can be deployed at all. The
# script classifies the failure and re-runs only a transfer problem; a compile or test failure
# exits immediately, because repeating it would bury the real error.
RUN ./infrastructure/build/mvn-package-retry.sh services/retrieval-comparison -DskipTests

FROM eclipse-temurin:21-jre

# Non-root matters most here: this is the only service holding the LiteLLM key (§8.3).
RUN useradd --system --uid 10001 appuser
WORKDIR /app

COPY --from=build /workspace/services/retrieval-comparison/target/*.jar /app/application.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]

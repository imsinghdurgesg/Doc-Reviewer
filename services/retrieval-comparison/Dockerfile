# retrieval-comparison image. Architecture §29.
#
# Build context is the repository root (§6 reactor layout).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

RUN ./mvnw -pl services/retrieval-comparison -am clean package -DskipTests

FROM eclipse-temurin:21-jre

# Non-root matters most here: this is the only service holding the LiteLLM key (§8.3).
RUN useradd --system --uid 10001 appuser
WORKDIR /app

COPY --from=build /workspace/services/retrieval-comparison/target/*.jar /app/application.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]

# document-processing image. Architecture §29, §5.3.
#
# Build context is the repository root (§6 reactor layout).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

RUN ./mvnw -pl services/document-processing -am clean package -DskipTests

FROM eclipse-temurin:21-jre

# LibreOffice headless for DOCX-to-PDF previews (§5.3). An OS package, NOT a Maven dependency —
# there is no JVM implementation of this conversion.
#
# --no-install-recommends keeps the image from pulling the full desktop suite. Fonts are installed as
# packages rather than copied into the repository (§29): without them LibreOffice substitutes
# metrically different faces and the rendered preview's text positions no longer match the bounding
# boxes extracted from the PDF — highlights land in the wrong place.
#
# libreoffice-CALC is required for CSV, and is a separate package from Writer. A .csv is loaded by
# Calc, so with Writer alone LibreOffice reports "source file could not be loaded" and the document
# gets no rendition at all — meaning no page geometry, so its evidence can never be highlighted even
# though the spans parse correctly. Naming the right export filter does not help if the application
# that reads the format is absent.
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-core \
        fonts-dejavu \
        fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app

COPY --from=build /workspace/services/document-processing/target/*.jar /app/application.jar

# LibreOffice writes a per-user profile on first run and fails if HOME is not writable — hence
# --create-home above. A read-only HOME makes conversion fail with an error that names neither
# LibreOffice nor permissions.
ENV HOME=/home/appuser

USER appuser
EXPOSE 8080

# Parsing holds a whole document plus its text positions in memory (§8.2 limits exist for this), so
# container-aware heap sizing matters more here than in the other services.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]

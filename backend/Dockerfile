# syntax=docker/dockerfile:1.7
FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp "$(ls target/*.jar | grep -v -- '-plain' | head -1)" /app.jar

FROM eclipse-temurin:25-jre-noble
# Tesseract languages for OCR, '+'-separated as in tesseract's -l (eng+nor+deu); each installs tesseract-ocr-<lang>.
# eng and osd (orientation detection) always come with tesseract-ocr.
ARG OCR_LANGUAGES=eng
# calibre (calibredb, ebook-convert) + poppler (pdftotext, pdftoppm) for metadata and text extraction, tesseract for OCR
RUN langs=$(echo "$OCR_LANGUAGES" | tr '+, ' '\n\n\n' | tr 'A-Z_' 'a-z-' | grep -vxE 'eng|osd|' | sed 's/^/tesseract-ocr-/') \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      calibre poppler-utils tesseract-ocr $langs curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV RAGGEDLIB_OCR_LANGUAGES=$OCR_LANGUAGES \
    QT_QPA_PLATFORM=offscreen \
    CALIBRE_CONFIG_DIRECTORY=/tmp/calibre-config \
    RAGGEDLIB_LIBRARY_PATH=/library/0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
RUN useradd --uid 1001 --create-home app
USER app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1.7
FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp "$(ls target/*.jar | grep -v -- '-plain' | head -1)" /app.jar

# Calibre from its official Linux binaries, pinned to the version that writes the libraries (desktop calibre on the
# host), rather than the distribution's older package: raggedlib reads and (for writable libraries) writes them with
# calibredb. To bump: change both values; the checksum is at https://calibre-ebook.com/signatures/calibre-<v>-x86_64.txz.sha512
FROM ubuntu:noble AS calibre
ARG CALIBRE_VERSION=9.18.0
ARG CALIBRE_SHA512=3ebb6cb35d0f990e17e13b24bbe3dc62069dce47c72ac4624539c91e9c956ea2635215d0614153db17aab69e377a8b59d28fc30665732d62d34f27751dc04041
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
 && curl -fsSL -o /tmp/calibre.txz "https://download.calibre-ebook.com/${CALIBRE_VERSION}/calibre-${CALIBRE_VERSION}-x86_64.txz" \
 && echo "${CALIBRE_SHA512}  /tmp/calibre.txz" | sha512sum -c - \
 && mkdir -p /opt/calibre && tar -xJf /tmp/calibre.txz -C /opt/calibre

FROM eclipse-temurin:25-jre-noble
# Tesseract languages for OCR, '+'-separated as in tesseract's -l (eng+nor+deu); each installs tesseract-ocr-<lang>.
# eng and osd (orientation detection) always come with tesseract-ocr.
ARG OCR_LANGUAGES=eng
# poppler (pdftotext, pdftoppm) for text extraction, tesseract for OCR, and the libraries calibre's bundled Qt needs
RUN langs=$(echo "$OCR_LANGUAGES" | tr '+, ' '\n\n\n' | tr 'A-Z_' 'a-z-' | grep -vxE 'eng|osd|' | sed 's/^/tesseract-ocr-/') \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      poppler-utils tesseract-ocr $langs curl ca-certificates \
      libegl1 libopengl0 libglx0 libxcb-cursor0 libxkbcommon0 libfontconfig1 libfreetype6 libnss3 \
 && rm -rf /var/lib/apt/lists/*
# calibre (calibredb, ebook-convert, ebook-meta, calibre-debug)
COPY --from=calibre /opt/calibre /opt/calibre
RUN for t in calibredb ebook-convert ebook-meta calibre-debug fetch-ebook-metadata; do ln -s /opt/calibre/$t /usr/local/bin/$t; done
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

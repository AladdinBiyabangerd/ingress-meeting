# ---- build ----
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon \
 && JAR=$(ls build/libs/*.jar | grep -v plain | head -1) \
 && cp "$JAR" /workspace/app.jar

# ---- runtime ----
FROM eclipse-temurin:25-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    WHISPER_MODEL=large-v3 \
    WHISPER_MODEL_DIR=/models/whisper \
    WHISPER_LANGUAGE=az \
    WHISPER_DEVICE=cpu \
    UPLOAD_DIR=/data/uploads \
    RESULTS_DIR=/data/results \
    PYTHONUNBUFFERED=1 \
    JAVA_OPTS="-Xms2g -Xmx8g"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 \
      python3-pip \
      python3-venv \
      ffmpeg \
      curl \
      ca-certificates \
      bash \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m pip install --no-cache-dir --upgrade pip \
 && python3 -m pip install --no-cache-dir \
      --extra-index-url https://download.pytorch.org/whl/cpu \
      torch \
      openai-whisper

WORKDIR /app

COPY --from=build /workspace/app.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh \
 && mkdir -p /models/whisper /data/uploads /data/results/partial /data/results/final

VOLUME ["/models/whisper", "/data/uploads", "/data/results"]
EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]

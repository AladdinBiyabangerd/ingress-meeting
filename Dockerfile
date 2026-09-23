# ---- Java build ----
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon \
 && JAR=$(ls build/libs/*.jar | grep -v plain | head -1) \
 && cp "$JAR" /workspace/app.jar

# ---- whisper.cpp build ----
FROM debian:bookworm-slim AS whisper-build
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential \
      cmake \
      git \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Pin a known release for reproducible builds
ARG WHISPER_CPP_REF=v1.7.5
WORKDIR /src
RUN git clone --depth 1 --branch "${WHISPER_CPP_REF}" https://github.com/ggerganov/whisper.cpp.git . \
 && cmake -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DGGML_NATIVE=OFF \
      -DBUILD_SHARED_LIBS=OFF \
 && cmake --build build -j"$(nproc)" --target whisper-cli \
 && test -x build/bin/whisper-cli \
 && mkdir -p /opt/whisper/bin /opt/whisper/lib \
 && cp build/bin/whisper-cli /opt/whisper/bin/whisper-cli \
 && find build -type f \( -name 'libwhisper.so*' -o -name 'libggml*.so*' \) -exec cp -a {} /opt/whisper/lib/ \; \
 && chmod +x /opt/whisper/bin/whisper-cli \
 && (ldd /opt/whisper/bin/whisper-cli || true)

# ---- runtime ----
FROM eclipse-temurin:25-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    WHISPER_MODEL=large-v3-q5_0 \
    WHISPER_MODEL_DIR=/models/whisper \
    WHISPER_LANGUAGE=az \
    WHISPER_THREADS=0 \
    WHISPER_CLI_PATH=/usr/local/bin/whisper-cli \
    LD_LIBRARY_PATH=/usr/local/lib \
    UPLOAD_DIR=/data/uploads \
    RESULTS_DIR=/data/results \
    JAVA_OPTS="-Xms512m -Xmx2g"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg \
      curl \
      ca-certificates \
      bash \
 && rm -rf /var/lib/apt/lists/*

COPY --from=whisper-build /opt/whisper/bin/whisper-cli /usr/local/bin/whisper-cli
COPY --from=whisper-build /opt/whisper/lib/ /usr/local/lib/
COPY --from=build /workspace/app.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

WORKDIR /app
RUN chmod +x /app/entrypoint.sh /usr/local/bin/whisper-cli \
 && mkdir -p /models/whisper /data/uploads /data/results/partial /data/results/final \
 && ldconfig \
 && (ldd /usr/local/bin/whisper-cli 2>&1 | tee /tmp/whisper-ldd.txt || true) \
 && if grep -q 'not found' /tmp/whisper-ldd.txt; then \
      echo "ERROR: whisper-cli missing shared libs:" && cat /tmp/whisper-ldd.txt && exit 1; \
    fi \
 && whisper-cli -h >/dev/null 2>&1 || true

VOLUME ["/models/whisper", "/data/uploads", "/data/results"]
EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]

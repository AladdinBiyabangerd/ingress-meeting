#!/usr/bin/env bash
set -euo pipefail

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [entrypoint] $*"
}

MODEL_NAME="${WHISPER_MODEL:-large-v3}"
MODEL_DIR="${WHISPER_MODEL_DIR:-/models/whisper}"
MODEL_FILE="${MODEL_DIR}/${MODEL_NAME}.pt"

log "Starting | model=${MODEL_NAME} dir=${MODEL_DIR}"

case "${MODEL_NAME}" in
  large-v3)
    MODEL_URL="https://openaipublic.azureedge.net/main/whisper/models/e5b1a55b89c1367dacf97e3e19bfd829a01529dbfdeefa8caeb59b3f1b81dadb/large-v3.pt"
    EXPECTED_MIN_BYTES=2800000000
    ;;
  large-v2)
    MODEL_URL="https://openaipublic.azureedge.net/main/whisper/models/81f7c96c852ee8fc832187b0132e569d28c15fdb/large-v2.pt"
    EXPECTED_MIN_BYTES=2800000000
    ;;
  medium)
    MODEL_URL="https://openaipublic.azureedge.net/main/whisper/models/345ae4da62f9b3d59415adc60127b45eabf702ca/medium.pt"
    EXPECTED_MIN_BYTES=1400000000
    ;;
  small)
    MODEL_URL="https://openaipublic.azureedge.net/main/whisper/models/9ecf779972d90ba49cfe6d0b7eb3d383ce663e63/small.pt"
    EXPECTED_MIN_BYTES=400000000
    ;;
  base)
    MODEL_URL="https://openaipublic.azureedge.net/main/whisper/models/ed3a0b6b1c0edf879ad9b11b1af5a0c6/base.pt"
    EXPECTED_MIN_BYTES=100000000
    ;;
  *)
    MODEL_URL=""
    EXPECTED_MIN_BYTES=0
    ;;
esac

mkdir -p "${MODEL_DIR}"

file_size=0
if [[ -f "${MODEL_FILE}" ]]; then
  file_size=$(stat -c%s "${MODEL_FILE}" 2>/dev/null || echo 0)
fi

if [[ ! -f "${MODEL_FILE}" ]] || [[ "${EXPECTED_MIN_BYTES}" -gt 0 && "${file_size}" -lt "${EXPECTED_MIN_BYTES}" ]]; then
  if [[ -z "${MODEL_URL}" ]]; then
    log "WARN: No direct URL for model '${MODEL_NAME}'; app will download via whisper.load_model"
  else
    log "Downloading Whisper model '${MODEL_NAME}' -> ${MODEL_FILE} (resume enabled)"
    curl -L --http1.1 -C - --retry 8 --retry-all-errors --retry-delay 3 \
      --progress-bar \
      -o "${MODEL_FILE}" \
      "${MODEL_URL}"
    log "Download complete: $(ls -lh "${MODEL_FILE}")"
  fi
else
  log "Model already present: ${MODEL_FILE} (${file_size} bytes)"
fi

export WHISPER_MODEL="${MODEL_NAME}"
export WHISPER_MODEL_DIR="${MODEL_DIR}"

log "Launching Spring Boot | JAVA_OPTS=${JAVA_OPTS:--Xms2g -Xmx8g}"
exec java ${JAVA_OPTS:--Xms2g -Xmx8g} -jar /app/app.jar

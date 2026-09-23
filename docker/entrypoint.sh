#!/usr/bin/env bash
set -euo pipefail

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [entrypoint] $*"
}

MODEL_NAME="${WHISPER_MODEL:-large-v3-q5_0}"
MODEL_DIR="${WHISPER_MODEL_DIR:-/models/whisper}"

# Map friendly names → ggml file on disk
case "${MODEL_NAME}" in
  *.bin)
    MODEL_FILE_NAME="${MODEL_NAME}"
    ;;
  ggml-*)
    MODEL_FILE_NAME="${MODEL_NAME}.bin"
    ;;
  *)
    MODEL_FILE_NAME="ggml-${MODEL_NAME}.bin"
    ;;
esac
MODEL_FILE="${MODEL_DIR}/${MODEL_FILE_NAME}"

# HuggingFace ggerganov/whisper.cpp mirrors
HF_BASE="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

case "${MODEL_FILE_NAME}" in
  ggml-large-v3-q5_0.bin)
    MODEL_URL="${HF_BASE}/ggml-large-v3-q5_0.bin"
    EXPECTED_MIN_BYTES=900000000
    ;;
  ggml-large-v3.bin)
    MODEL_URL="${HF_BASE}/ggml-large-v3.bin"
    EXPECTED_MIN_BYTES=2800000000
    ;;
  ggml-large-v3-q8_0.bin)
    MODEL_URL="${HF_BASE}/ggml-large-v3-q8_0.bin"
    EXPECTED_MIN_BYTES=1400000000
    ;;
  ggml-medium.bin)
    MODEL_URL="${HF_BASE}/ggml-medium.bin"
    EXPECTED_MIN_BYTES=1400000000
    ;;
  ggml-medium-q5_0.bin)
    MODEL_URL="${HF_BASE}/ggml-medium-q5_0.bin"
    EXPECTED_MIN_BYTES=450000000
    ;;
  ggml-small.bin)
    MODEL_URL="${HF_BASE}/ggml-small.bin"
    EXPECTED_MIN_BYTES=400000000
    ;;
  ggml-base.bin)
    MODEL_URL="${HF_BASE}/ggml-base.bin"
    EXPECTED_MIN_BYTES=100000000
    ;;
  *)
    MODEL_URL="${HF_BASE}/${MODEL_FILE_NAME}"
    EXPECTED_MIN_BYTES=0
    ;;
esac

log "Starting | engine=whisper.cpp model=${MODEL_NAME} file=${MODEL_FILE}"

mkdir -p "${MODEL_DIR}"

file_size=0
if [[ -f "${MODEL_FILE}" ]]; then
  file_size=$(stat -c%s "${MODEL_FILE}" 2>/dev/null || echo 0)
fi

if [[ ! -f "${MODEL_FILE}" ]] || [[ "${EXPECTED_MIN_BYTES}" -gt 0 && "${file_size}" -lt "${EXPECTED_MIN_BYTES}" ]]; then
  log "Downloading ggml model -> ${MODEL_FILE} (resume enabled)"
  curl -L --http1.1 -C - --retry 8 --retry-all-errors --retry-delay 3 \
    --progress-bar \
    -o "${MODEL_FILE}" \
    "${MODEL_URL}"
  log "Download complete: $(ls -lh "${MODEL_FILE}")"
else
  log "Model already present: ${MODEL_FILE} (${file_size} bytes)"
fi

if ! command -v whisper-cli >/dev/null 2>&1; then
  log "ERROR: whisper-cli not found on PATH"
  exit 1
fi

export WHISPER_MODEL="${MODEL_NAME}"
export WHISPER_MODEL_DIR="${MODEL_DIR}"

log "Launching Spring Boot | JAVA_OPTS=${JAVA_OPTS:--Xms512m -Xmx2g}"
exec java ${JAVA_OPTS:--Xms512m -Xmx2g} -jar /app/app.jar

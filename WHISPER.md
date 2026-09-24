# Transcription engine

Meeting uses **whisper.cpp** (`whisper-cli`), not Python/PyTorch OpenAI Whisper.

1. `ffmpeg` converts the upload to 16 kHz mono WAV
2. `whisper-cli` runs with a ggml model from `/models/whisper`
3. Default model: `large-v3-q5_0` (quantized — much faster on CPU)

## Deploy / rebuild

```bash
cd ~/ingress-meeting
git pull
docker compose build --no-cache
docker compose up -d
docker compose logs -f
```

Look for: `Whisper.cpp ready | cli=... model=...ggml-medium.bin`

The image ships `whisper-cli` **and** its shared libs (`libwhisper` / `libggml`) with
`LD_LIBRARY_PATH=/usr/local/lib`. If you ever see
`error while loading shared libraries: libwhisper.so.1`, rebuild with `--no-cache`.

## Env

| Variable | Default | Notes |
|----------|---------|--------|
| `WHISPER_MODEL` | `medium` | Also: `large-v3-q5_0`, `large-v3`, `medium-q5_0`, `small` |
| `WHISPER_THREADS` | `0` (all cores) | Set e.g. `8` to cap |
| `WHISPER_LANGUAGE` | `az` | |
| `ACADEMY_CALLBACK_BASE_URL` | — | Academy callback base |
| `API_SECRET_KEY` | — | Shared with Academy |

Meeting only transcribes. After Whisper finishes it POSTs `COMPLETED` +
`transcription` to Academy. Lesson `summary` / `topics` are built on Academy
(OpenAI) once every video part has a transcript.

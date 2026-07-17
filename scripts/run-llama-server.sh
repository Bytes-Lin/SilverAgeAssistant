#!/usr/bin/env bash
set -euo pipefail

: "${LLAMA_MODEL:?Set LLAMA_MODEL to an absolute .gguf model path}"
LLAMA_PORT="${LLAMA_PORT:-8080}"
LLAMA_CONTEXT="${LLAMA_CONTEXT:-8192}"
LLAMA_ALIAS="${LLAMA_ALIAS:-local-model}"

exec llama-server \
  -m "$LLAMA_MODEL" \
  --host 0.0.0.0 \
  --port "$LLAMA_PORT" \
  -c "$LLAMA_CONTEXT" \
  --alias "$LLAMA_ALIAS"

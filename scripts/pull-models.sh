#!/usr/bin/env bash
# Pull the embedding + chat models into the dockerised Ollama.
set -euo pipefail
EMBED_MODEL="${EMBED_MODEL:-qwen3-embedding:0.6b}"
CHAT_MODEL="${CHAT_MODEL:-qwen3.5:9b}"
for m in "$EMBED_MODEL" "$CHAT_MODEL"; do
  echo ">> pulling $m"
  docker compose exec -T ollama ollama pull "$m"
done

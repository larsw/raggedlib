#!/usr/bin/env bash
# Fetch every model the stack needs, ahead of first use:
#  - the embedding + chat models, into the dockerized Ollama;
#  - the reranker (bge-reranker-v2-m3 GGUF, ~640 MB), which the llama.cpp `reranker` container downloads from
#    Hugging Face into its `reranker` volume on first start. Until it has, searches fall back to the unreranked order.
set -euo pipefail
cd "$(dirname "$0")/.."
EMBED_MODEL="${EMBED_MODEL:-qwen3-embedding:0.6b}"
CHAT_MODEL="${CHAT_MODEL:-qwen3.5:9b}"
RERANKER_TIMEOUT="${RERANKER_TIMEOUT:-1200}" # seconds to wait for the download + load

docker compose up -d ollama
for m in "$EMBED_MODEL" "$CHAT_MODEL"; do
  echo ">> pulling $m"
  docker compose exec -T ollama ollama pull "$m"
done

echo ">> starting the reranker (downloads its model on first start)"
docker compose up -d reranker
container="$(docker compose ps -q reranker)"
deadline=$((SECONDS + RERANKER_TIMEOUT))
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$container")" = healthy ]; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$container")" != true ]; then
    echo "!! reranker container stopped; last log lines:" >&2
    docker compose logs --tail 20 reranker >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "!! reranker not healthy after ${RERANKER_TIMEOUT}s (still downloading?): docker compose logs -f reranker" >&2
    exit 1
  fi
  sleep 5
done
echo ">> reranker ready"

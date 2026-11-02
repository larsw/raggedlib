#!/usr/bin/env bash
# Section-label surveys over a random sample of the library's PDFs (needs pdftotext and the library on this host):
#  - SectionLabelSurvey: TOC-aware labelling vs the old heuristic (front matter labelled with a late chapter,
#    real labels lost);
#  - RunningHeadSurvey: labels with vs without removing page-numbered running heads.
# usage: scripts/search-eval/label_survey.sh [sample-size]   (default 60)
set -euo pipefail
cd "$(dirname "$0")/../.."
N="${1:-60}"
LIB="${RAGGEDLIB_LIBRARY:-$HOME/Documents/CalibreLibrary}"
list="$(mktemp)"
trap 'rm -f "$list"' EXIT
docker compose exec -T postgres psql -U raggedlib -d raggedlib -tA \
  -c "select formats->>'PDF' from books where status = 'INDEXED' and source_format = 'PDF' order by random() limit $N" \
  | sed "s|^|$LIB/|" > "$list"
echo ">> $(wc -l < "$list") PDFs"
cd backend
out="$(mktemp)"
trap 'rm -f "$list" "$out"' EXIT
for t in SectionLabelSurvey RunningHeadSurvey; do
  echo ">> $t"
  if RAGGEDLIB_SURVEY_PDFS="$list" mvn -q test -Dtest="$t" -Dsurefire.failIfNoSpecifiedTests=false >"$out" 2>&1; then
    grep -v '^\[\|^WARNING' "$out" || true
  else
    cat "$out" >&2 # compile errors and test failures come as [ERROR] lines
    exit 1
  fi
done

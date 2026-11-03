#!/usr/bin/env bash
# Rebuild docs/report/stage2-report.pdf from docs/report/stage2-report.html with headless Chrome.
# Usage: docs/report/build-report.sh   (run from anywhere)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [ ! -x "$CHROME" ]; then
  echo "Chrome not found at: $CHROME (set CHROME=/path/to/chrome)" >&2
  exit 1
fi
"$CHROME" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$HERE/stage2-report.pdf" \
  "file://$HERE/stage2-report.html"
echo "Wrote $HERE/stage2-report.pdf"

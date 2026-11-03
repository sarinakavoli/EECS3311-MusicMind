#!/usr/bin/env bash
# Rebuild docs/report/stage2-report.pdf: first crop the UML figures (needs python3 + Pillow),
# then render docs/report/stage2-report.html with headless Chrome.
# Usage: docs/report/build-report.sh   (run from anywhere)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

if ! python3 -c 'import PIL' 2>/dev/null; then
  echo "Python 3 with Pillow is needed to crop the figures: pip install pillow" >&2
  exit 1
fi
if [ ! -x "$CHROME" ]; then
  echo "Chrome not found at: $CHROME (set CHROME=/path/to/chrome)" >&2
  exit 1
fi

python3 "$HERE/make-figures.py"

URL="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().as_uri())' "$HERE/stage2-report.html")"
"$CHROME" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$HERE/stage2-report.pdf" "$URL"
echo "Wrote $HERE/stage2-report.pdf"

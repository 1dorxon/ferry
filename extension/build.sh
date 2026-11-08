#!/bin/sh
# Package the Chrome extension for the Chrome Web Store: dist/ferry-chrome-<version>.zip.
# Everything in the zip is this folder's source as is: no bundler, no build step.
set -eu
cd "$(dirname "$0")"
V=$(python3 -c "import json; print(json.load(open('manifest.json'))['version'])")
grep -q "SERVER = 'https://ferry.luitlabs.in'" config.js || { echo "config.js is not pointed at production" >&2; exit 1; }
mkdir -p dist
OUT="dist/ferry-chrome-$V.zip"
rm -f "$OUT"
python3 - "$OUT" manifest.json background.js config.js engine.html engine.js panel.html panel.css panel.js theme.js icons img <<'PY'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    for top in sys.argv[2:]:
        for path in sorted([top] if os.path.isfile(top) else [os.path.join(d, f) for d, _, fs in os.walk(top) for f in fs]):
            z.write(path)
PY
echo "$OUT ($(du -h "$OUT" | cut -f1))"

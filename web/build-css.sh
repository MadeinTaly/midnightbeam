#!/usr/bin/env bash
# Development only: compiles the Tailwind stylesheet of the phone remote page and copies the page into the
# app assets. The generated files are committed, so building the APK does not need Node.
set -euo pipefail
cd "$(dirname "$0")"
npx --yes tailwindcss@3 -c tailwind.config.js -i input.css -o ../app/assets/remote.css --minify
cp remote.html ../app/assets/remote.html
echo "remote.css $(stat -c %s ../app/assets/remote.css) bytes, remote.html $(stat -c %s ../app/assets/remote.html) bytes"

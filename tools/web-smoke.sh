#!/bin/bash
#
# Clicks through the web application's microcode page in a headless Chrome and checks what it
# shows: the predecessor links, Back and Next, switching document, the search orders, the
# KD11-B revision difference and a not-found. Not part of the build - CI has no Chrome and no
# running server - but the only thing that exercises the page as a browser does.
#
#   ./mvnw -pl pdp11-web jetty:run      (in another terminal)
#   tools/web-smoke.sh [http://localhost:8080]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
url="${1:-http://localhost:8080}"
port=9333
profile="$(mktemp -d)"
cleanup() {
	kill "$chrome" 2>/dev/null || true
	wait "$chrome" 2>/dev/null || true
	#-- Chrome's helper processes outlive the browser by a moment and keep writing the profile.
	for _ in 1 2 3 4 5 6 7 8 9 10; do
		rm -rf "$profile" 2>/dev/null && return
		sleep 0.3
	done
}
trap cleanup EXIT

google-chrome --headless=new --no-sandbox --disable-gpu --user-data-dir="$profile" \
	--remote-debugging-port=$port --window-size=1200,1400 about:blank >/dev/null 2>&1 &
chrome=$!

CDP_PORT=$port WEB_URL="$url" node "$root/tools/web-smoke.mjs"

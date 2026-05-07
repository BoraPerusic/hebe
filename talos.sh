#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/gradlew" --quiet --console=plain :modules:cli-app:run --args="$*"

#!/usr/bin/env bash
# version.sh — print the template/project version (from the VERSION file).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

if [ -f "$TEMPLATE_ROOT/VERSION" ]; then
    cat "$TEMPLATE_ROOT/VERSION"
else
    echo "unknown"
fi

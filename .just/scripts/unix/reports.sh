#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"

REPORTS_DIR="$TEMPLATE_ROOT/reports"
mkdir -p "$REPORTS_DIR"

action="${1:-all}"
case "$action" in
    all)
        ts="$(date +%Y%m%d-%H%M%S)"
        out="$REPORTS_DIR/report-$ts.txt"
        info "Generating report -> $out"
        {
            echo "Report generated: $ts"
            echo "Platform:         $(detect_platform)"
            echo "Project root:     $TEMPLATE_ROOT"
        } > "$out"
        ok "Report written: $out"
        ;;
    *)
        die "Unknown reports action: $action (use all)"
        ;;
esac

#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

# Resolve the Android SDK and export ANDROID_HOME for the Gradle wrapper.
sdk_guard() {
    local sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
    [ -d "$sdk" ] || die "Android SDK not found. Set ANDROID_HOME or install it in \$HOME/Android/Sdk — see: just pending"
    export ANDROID_HOME="$sdk"
}
gw() { ./android/gradlew -p android "$@" --no-daemon; }

action="${1:-}"
case "$action" in
    build)
        sdk_guard
        info "Building the debug APK…"
        gw assembleDebug
        ok "APK: android/app/build/outputs/apk/debug/app-debug.apk"
        ;;
    install)
        sdk_guard
        info "Building and installing the debug APK…"
        gw installDebug
        ok "Installed on the connected device/emulator."
        ;;
    install-lan)
        sdk_guard
        [ -f .env ] || die "Missing .env — run: just setup"
        host="${2:-}"
        [ -n "$host" ] || host="$(ip route get 1.1.1.1 | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
        port="$(grep -E '^API_PORT=' .env | head -1 | sed 's/^[^=]*=//')"; port="${port:-8080}"
        info "Installing with API URL http://$host:$port/"
        gw installDebug -PAPI_BASE_URL="http://$host:$port/"
        ok "Installed (LAN-configured)."
        ;;
    test)
        sdk_guard
        info "Running Android JVM unit tests…"
        gw testDebugUnitTest
        ok "Android tests passed."
        ;;
    clean)
        sdk_guard
        info "Removing Android build artifacts…"
        gw clean
        ok "Android build cleaned."
        ;;
    *)
        die "Unknown mobile action: $action (use build|install|install-lan [IP]|test|clean)"
        ;;
esac

#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

# Backend: Kotlin/Gradle tests run in an isolated Gradle container (no local JDK/Gradle needed).
test_backend() {
    command -v docker >/dev/null 2>&1 || die "docker missing — see: just pending"
    info "Running backend JVM tests (Gradle container)…"
    docker run --rm --user "$(id -u):$(id -g)" \
        -e GRADLE_USER_HOME=/tmp/gradle \
        -v "$PWD/backend:/src" -w /src \
        gradle:8.12-jdk17 gradle test --project-cache-dir /tmp/project-cache --no-daemon
    ok "Backend tests passed."
}

# Android: JVM unit tests via the Gradle wrapper (needs the Android SDK + JDK).
test_android() {
    local sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
    [ -d "$sdk" ] || die "Android SDK not found. Set ANDROID_HOME or install it in \$HOME/Android/Sdk — see: just pending"
    info "Running Android unit tests…"
    ANDROID_HOME="$sdk" ./android/gradlew -p android testDebugUnitTest --no-daemon
    ok "Android tests passed."
}

action="${1:-all}"
case "$action" in
    all)     test_backend; test_android ;;
    backend) test_backend ;;
    android) test_android ;;
    *)       die "Unknown tests action: $action (use all|backend|android)" ;;
esac

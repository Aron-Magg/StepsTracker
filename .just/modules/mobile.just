# mobile.just — Android app (optional module, loaded via `mod? mobile`).
# HOW lives in .just/scripts/unix/mobile.sh; this file is only the WHAT.

set working-directory := '../..'

_unix := '.just/scripts/unix'

# Show the mobile subcommands.
default:
    @echo "mobile subcommands: build, install, install-lan [IP], test, clean"

# Build the debug APK (android/app/build/outputs/apk/debug/app-debug.apk).
build:
    @bash {{_unix}}/mobile.sh build

# Build and install the debug APK on a connected device or emulator.
install:
    @bash {{_unix}}/mobile.sh install

# Install a debug APK pointed at this computer's LAN API (IP auto-detected if omitted).
install-lan IP="":
    @bash {{_unix}}/mobile.sh install-lan '{{IP}}'

# Run the Android JVM unit tests.
test:
    @bash {{_unix}}/mobile.sh test

# Remove Android build artifacts.
clean:
    @bash {{_unix}}/mobile.sh clean

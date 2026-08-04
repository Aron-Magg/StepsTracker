# tests.just — test suites (backend Kotlin/Gradle + Android JVM unit tests).

set working-directory := '../..'

_unix := '.just/scripts/unix'

# Show the tests subcommands.
default:
    @echo "tests subcommands: all, backend, android"

# Run every test suite (backend + Android).
all:
    @bash {{_unix}}/tests.sh all

# Run only the backend JVM tests.
backend:
    @bash {{_unix}}/tests.sh backend

# Run only the Android JVM unit tests.
android:
    @bash {{_unix}}/tests.sh android

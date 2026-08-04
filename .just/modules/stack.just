# stack.just — Docker Compose stack + production operations (optional module).
# HOW lives in .just/scripts/unix/stack.sh; this file is only the WHAT.

set working-directory := '../..'
set dotenv-load := true
set dotenv-required := false

_unix := '.just/scripts/unix'

# Show the stack subcommands.
default:
    @echo "stack subcommands: lan-up, down, build, clean, status, logs [SVC], api-health, lan-ip, backend-build, backend-clean, prod-up, prod-down, prod-status, prod-logs"

# Start the local stack on the trusted LAN for phone access (API on 0.0.0.0).
lan-up:
    @bash {{_unix}}/stack.sh lan-up

# Stop the local stack while preserving the database volume.
down:
    @bash {{_unix}}/stack.sh down

# Build the backend image and the Android debug APK.
build:
    @bash {{_unix}}/stack.sh build

# Remove backend and Android build artifacts.
clean:
    @bash {{_unix}}/stack.sh clean

# Show the current local service status.
status:
    @bash {{_unix}}/stack.sh status

# Follow logs from one service, or every service if SVC is empty.
logs SVC="":
    @bash {{_unix}}/stack.sh logs '{{SVC}}'

# Check the local API health endpoint.
api-health:
    @bash {{_unix}}/stack.sh api-health

# Print this computer's primary local IPv4 address.
lan-ip:
    @bash {{_unix}}/stack.sh lan-ip

# Build the backend Docker image.
backend-build:
    @bash {{_unix}}/stack.sh backend-build

# Remove locally generated backend artifacts.
backend-clean:
    @bash {{_unix}}/stack.sh backend-clean

# Start the VPS/production stack with Caddy and HTTPS.
[confirm("Start the production stack (Caddy + HTTPS)?")]
prod-up:
    @bash {{_unix}}/stack.sh prod-up

# Stop the production stack while preserving volumes.
prod-down:
    @bash {{_unix}}/stack.sh prod-down

# Show production stack status.
prod-status:
    @bash {{_unix}}/stack.sh prod-status

# Follow production stack logs.
prod-logs:
    @bash {{_unix}}/stack.sh prod-logs

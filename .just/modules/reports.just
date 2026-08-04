# reports.just — generated reports (written into reports/).

set working-directory := '../..'

_unix := '.just/scripts/unix'

# Show the reports subcommands.
default:
    @echo "reports subcommands: all"

# Generate every report.
all:
    @bash {{_unix}}/reports.sh all

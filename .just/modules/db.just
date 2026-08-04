# db.just — database lifecycle. Guarded by DB_ENABLED in .env: when false every
# recipe no-ops safely, so the module ships enabled-by-nothing.

set working-directory := '../..'
set dotenv-load := true
set dotenv-required := false

_unix := '.just/scripts/unix'

# Show the db subcommands.
default:
    @echo "db subcommands: backup, restore FILE, reset"

# Back up the database into backups/db/.
backup:
    @bash {{_unix}}/db.sh backup

# Restore the database from a dump file.
[confirm("Restore the database from the given file? This overwrites current data.")]
restore FILE:
    @bash {{_unix}}/db.sh restore '{{FILE}}'

# Reset the database to a clean state.
[confirm("Reset the database? This is destructive.")]
reset:
    @bash {{_unix}}/db.sh reset

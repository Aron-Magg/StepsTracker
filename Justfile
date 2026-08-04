# ══════════════════════════════════════════════════════════════════════════
#  Project Justfile — generated from the JustfileConfigurator template
#
#  Five-layer architecture:
#    Justfile → modules (WHAT) → scripts → OS adapters (HOW) → manifests (data)
#
#  Run `just` for the grouped recipe list, or `just --groups`.
# ══════════════════════════════════════════════════════════════════════════

set shell := ["bash", "-eu", "-o", "pipefail", "-c"]
set dotenv-load := true
set dotenv-required := false

# ── Entry point ────────────────────────────────────────────────────────────

# Show every recipe, grouped.
default:
    @just --list

# ── Core recipes (imported) ────────────────────────────────────────────────
# Flat lifecycle / platform / diagnostics recipes, grouped for `just --list`.
import '.just/modules/project.just'

# ── Modules ─────────────────────────────────────────────────────────────────
# Namespaced: `just db backup` or `just db::backup`.

mod config  '.just/modules/config.just'
mod db      '.just/modules/db.just'
mod tests   '.just/modules/tests.just'
mod reports '.just/modules/reports.just'

# ── Optional modules ────────────────────────────────────────────────────────
# Loaded with `mod?`; delete a file or its line freely.

mod? mobile '.just/modules/mobile.just'   # Android app (build/install/test)
mod? stack  '.just/modules/stack.just'    # Docker Compose stack + production ops

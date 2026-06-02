# AGENTS.md

Ni-Launcher is a small offline Android launcher. Keep it simple, explicit, and easy to delete from.

## Agent rules
- Do not change app code unless the task asks for code changes.
- Prefer the smallest useful change; avoid "just in case" structure.
- If a decision is unclear, ask or leave a grepable `TODO:` in the relevant doc.
- Before reporting code changes as done, run the relevant Gradle check.

## Project map
- App code: `app/src/main/java/com/ni/launcher/`
- Tests: `app/src/test/`
- Project docs: `docs/`
- Build logic: Gradle Kotlin DSL + `gradle/libs.versions.toml`

Use `docs/` for product intent, architecture, and design decisions. Keep this file short.

# 0004 Keep Room (write-behind) instead of a single-file snapshot

Status: accepted for now (revisit)

## Context
The whole user state is small: 42 tags / 330 edges in the base data (122 / 1084 in x3), a few
tens of KB. Since the in-memory graph became the source of truth (read once at startup, written
behind with a 150 ms debounce), Room is only a load/save format. A single file (JSON or a small
binary format, written to a temp file then renamed) would remove the entities, DAOs, the
database class and the schema JSON, maybe 150-200 lines.

## Decision
Keep Room in v5. Fold the Room declarations (entities, DAOs, AppDatabase) into
`data/RoomGraphPersister.kt` (one file for the whole persistence layer) and drop the custom
`Converters` (Room 2.8 stores enums by name, as TEXT, which is what Converters did, so the
schema is unchanged).

## Consequences
- 5 files → 1, −~40 lines, no change to the on-disk format, no migration.
- The bigger cut (single-file snapshot) stays open: it needs a one-time Room → file migration
  for existing installs, and e2e tests (bench-e2e) seed and read the DB directly.
- Unmeasured: startup cost of Room vs a file read on device. Room's open + first query is
  usually milliseconds; a file of tens of KB parses in about the same time. Measure before deciding.

## Alternatives
- Single-file snapshot (JSON via kotlinx.serialization, or a hand-written format): smallest code, atomic
  rename gives crash safety, but costs a migration and new e2e seeding. Deferred.
- DataStore (Proto): fits small state, adds a dependency and a schema file. Not tried.

## Sources
- Room overview: https://developer.android.com/training/data-storage/room
- SQLite performance best practices: https://developer.android.com/topic/performance/sqlite-performance-best-practices
- JSON parser benchmarks on Android: https://github.com/RicardoJiang/json-benchmark

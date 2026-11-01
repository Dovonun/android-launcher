# 0003 Group system data by lifetime (SystemData), user tags separately

Status: accepted (v4, kept in v5)

## Context
User tags change on every edit; apps and shortcuts change only when SystemSource publishes (install,
uninstall, shortcut update). v3 (93af050) merged both into one `Graph` and made `appByPkg` a
`by lazy` on Graph, so every user edit created a new Graph and rebuilt the package index:
+8.2 KB/op and about +20% time on `proj_tagView`, +20..47% on edit-until-tagView. v3b (18a0d57) fixed
that with a global `@Volatile` one-entry cache keyed by the apps map.

## Decision
`data class SystemData(apps, shortcuts)` owns the derived index (`val appByPkg by lazy`). `Graph` holds
`system: SystemData`; user edits `copy` the Graph and share the same SystemData instance, so its
index is built once per system snapshot. System changes go through `Command.SystemChanged(s)`,
which returns the same Graph when `s` is equal to the current one.

## Consequences
- No global cache, no invalidation code; the index lives exactly as long as the data it indexes.
- `rebuild_systemChange` (JVM, preliminary): v2 130 µs → v4 36 µs.
- RoomGraphPersister skips system changes with `if (old.tags === new.tags) return`.

## Alternatives
- v3b's global cache: works, but hides the lifetime in a side table. Rejected.
- Separate StateFlows for user and system data: two sources of truth, combine() ordering issues. Rejected.

## Sources
- Ryan Fleury, "Untangling Lifetimes: The Arena Allocator": https://www.dgtlgrove.com/p/untangling-lifetimes-the-arena-allocator (group things by when they die)
- Chris Wellons, arena allocation tips: https://nullprogram.com/blog/2023/09/27/

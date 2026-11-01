# 0005 UI types: keep LauncherItem sealed, remove duplicate key types

Status: accepted (v5)

## Context
The owner prefers tagged unions or one struct with a kind field over hierarchies, and allowed
breaking up the sealed UI classes. Candidates: `LauncherItem` (App / Shortcut / Tag / Placeholder),
`View`, `MenuState`, and a private `SelectionKey` in ManageTagScreen.

## Decision
- Delete `SelectionKey` + `selectionKey()`: they were a copy of `ItemKey` + `itemKey()`
  (same three cases, same fields). The picker now keys its selection by `ItemKey`.
- Keep `LauncherItem` as a sealed interface of data classes. In Kotlin that *is* the tagged union:
  one tag (the class), exhaustive `when`, no virtual calls except `label`/`icon` on Tag. A single
  data class with a `kind` and nullable `app` / `shortcut` / `tag` fields would trade the types in
  `List<LauncherItem.Tag>`, `View.ManageTag(tag: LauncherItem.Tag)` and `filterIsInstance` for `!!`
  at ~76 use sites, for a saving of ~20 lines in LauncherItem.kt.
- Keep `View` and `MenuState` (small, already data objects / data classes).

## Consequences
- −12 lines, one key type for "which graph item is this" across model, persister and UI.
- Unmeasured: Compose skipping. Projections rebuild LauncherItems that are equal but not the same
  instance; with strong skipping (Kotlin 2.0.20+) unstable parameters are compared by instance, so
  rows may recompose after every tagView rebuild. Not measured; would need a recomposition count on device.

## Alternatives
- `data class LauncherItem(kind, label, icon, app?, shortcut?, tagId, name, rep?, count)`. Rejected (see above).

## Sources
- Strong skipping mode: https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Brian Will, "How to program without OOP": https://medium.com/@brianwill/how-to-program-without-oop-74a46e0e47a3

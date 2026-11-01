# 0006 One letter bar, one popup shell, one view switch

Status: accepted (v5)

## Context
The All Apps screen and the Add-items picker each had their own letter bar (~100 lines each,
same layout, same animation, same drag math; they differed in the letters, the scroll targets,
a text shadow, hiding the letters outside All Apps, and switching the view while dragging).
The swipe popup and the picker's shortcut popup repeated the same positioning code (anchor,
safe area, 5 visible rows, dismiss layer). MainActivity switched screens with an if/else chain
and then a second `when` with dead `-> Unit` arms.

## Decision
- One `LetterBar(letters, scrollIndexes, listState, showLetters, shadow, onTouch)` in MainActivity.kt.
  Callers compute their own letters and scroll targets; All Apps passes
  `onTouch = { onLetter -> setView(if (onLetter == false) Favorites else AllApps) }`, which keeps
  the old event order (touch down → AllApps, then scroll; dragging off the letters → Favorites).
- One `AnchoredPopup(anchorY, rows, onDismiss) { panel -> ... }`: the shell hands the content the
  positioned panel modifier; each popup keeps its own list (Column vs LazyColumn) and empty state.
  TagPreviewPopup keeps its own math (different row heights).
- One `Modifier.onSwipeRight(keys, enabled) { bottomY -> }` for the three rows that open a popup
  on a right swipe (IconRow, the picker's app row, the tag manager row); same 50 px threshold,
  once per drag, same captured-state behavior as the copies.
- One `when (val v = currentView)` for all screens.
- ReorderRow (ManageTag) is `LauncherRowLayout(item, label = tag name, overflow = Clip)`.
- One launch-target resolver (`resolveLaunchTarget`) for launching and for the sheet's app actions;
  on a representative cycle (never built by tagViewOf) it now returns the RECURSION placeholder
  instead of throwing.
- The picker's confirm builds its additions with one filter over tags, apps, then opened shortcuts
  (same order as the three loops it replaces).
- Small cleanups: `AppsVM.updateOrder` (an alias of `syncTagItemsToList`) removed; defensive
  `toList()` copies of immutable lists removed; one redundant Box/Row level in TagManagerScreen.

## Consequences
- See line counts in the v5 commit / ANALYSIS.md. Behavior is meant to be identical; verified by
  build + e2e on device (pending), not by unit tests.
- Unmeasured: none of this is on a hot path; no speed change expected.

## Alternatives
- Leave the two letter bars (each screen self-contained, Brian Will style locality). Rejected: the
  shared function sits in the file both screens already import from, and the copies had started to drift.

## Sources
- Brian Will, "Object-Oriented Programming is Bad": https://www.youtube.com/watch?v=QM1iUe6IofM

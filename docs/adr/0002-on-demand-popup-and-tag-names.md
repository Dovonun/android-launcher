# 0002 Compute popup entries and tag-name badges on demand

Status: accepted (v4, kept in v5)

## Context
v2/v3/v3b kept two full projections up to date: `appPopups` (the swipe popup of every package with
shortcuts, 60 packages in the base data) and `tagsByKey` (for every item, the tags that contain it).
The UI reads one entry, when the user opens a popup or the context sheet. Keeping them current cost
~43% of an edit's CPU and ~50% of its bytes.

## Decision
Delete both projections. `appPopupOf(graph, pkg)` and `tagNamesOf(graph, key)` (model/Graph.kt) run
when the popup / sheet opens, on the current snapshot. Both scan the tags once and sort only the
tags that reference the package/key.

## Consequences
- Edits no longer pay for them (see 0001 numbers). `d_appPopup` (open a popup after an edit): 46 → 3.3 µs.
- Opening a popup with no edit before it is slower: `d_popupReadOnly` 0.055 µs (map lookup) → ~2.4 µs
  (preliminary, single run). 2.4 µs is far below one frame (16 ms), so the trade is fine; it is a real
  regression in that microbenchmark and is reported as such.
- The tag-name badge is a Flow mapped from the graph with `distinctUntilChanged`, so it still updates live.

## Alternatives
- Lazy per-snapshot cache of all popups: same worst case, more code. Rejected.
- Memoize the last (snapshot, pkg) pair: saves 2 µs on repeated opens; not worth a cache. Rejected.

## Sources
- Mike Acton, Data-Oriented Design ("solve for the common case; where there is one there are many"): https://neil3d.github.io/assets/img/ecs/DOD-Cpp.pdf

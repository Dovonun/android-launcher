# 0001 Recompute a projection only when its inputs changed (by identity)

Status: accepted (v4, kept in v5)

## Context
Every graph snapshot used to rerun every projection (tagView, allApps, appPopups, tagsByKey),
even when the edit touched one tag. Profiling `b_editTag` on v2 (d40c7d9, JVM bench, async-profiler,
base dataset 155 apps / 300 shortcuts / 42 tags / 330 edges):

| part of one edit | CPU | bytes |
|---|---|---|
| allApps (sort + group all apps) | 34% | 39% |
| appPopups (popups of 60 packages) | 30% | 34% |
| tagsByKey | 13% | 16% |
| tagView | 12% | 9% |
| equals / apply | 9% | — |

About 77% of an edit was spent rebuilding rows whose inputs had not changed.

## Decision
Each projection names its inputs as getters on the snapshot and reruns only when one of them is
a different instance:

```kotlin
val tagView = project(TagView.EMPTY, { it.tags }, { it.system }, f = ::tagViewOf)
val allApps = project(emptyMap(), { it.system }, { it.tags[TAG.PINNED] }, f = ::allAppsOf)

private fun <B> project(initial: B, vararg inputs: (Graph) -> Any?, f: (Graph) -> B) =
    graph.distinctUntilChanged { a, b -> inputs.all { it(a) === it(b) } }.derive(initial, f)
```

This works because edits keep untouched values by identity (`Graph.editTag` returns `this` when
nothing changed; `copy` shares every other field).

allApps also lowercases each label once (decorate-sort) instead of twice per comparison;
`String.lowercase` inside the comparator was 35% of all bytes allocated per edit.

## Consequences
- b_editTag (base, JVM, quick single run, preliminary): v2 ~105-113 µs / 296 KB → v4 ~15-16 µs / 30 KB.
  Final 4-round numbers: see /workspace/graph-perf-analysis/results-v4/TABLES.md.
- An edit that must change the inputs' identity but not their content still recomputes (correct, just not free).
- Correctness depends on edits not mutating in place. Everything is immutable `data class` + read-only maps, so this holds.

## Alternatives
- Incremental projections (patch one tag's rows): faster still (~1-2 µs estimated), much more code. Rejected for size.
- `equals` instead of `===` on inputs: deep compares cost as much as part of the rebuild. Rejected.

## Sources
- Casey Muratori, "Clean Code, Horrible Performance": https://www.computerenhance.com/p/clean-code-horrible-performance (count the work that actually has to happen)
- Compose phases / doing less work per frame: https://developer.android.com/develop/ui/compose/performance/phases

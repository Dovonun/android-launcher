# AGENTS.md

## Project-Specific Notes (Read First)
This launcher intentionally favors a small, explicit codebase over generic abstractions.
- Local build note: set `JAVA_HOME=/snap/android-studio/current/jbr` before running Gradle commands.

## Style Expectations
- Prefer minimal, functional code paths.
- Avoid "just in case" abstractions.
- Keep behavior explicit in call sites unless duplication is noisy boilerplate.
- "No line is best line" is the default.

## Core Architecture Conventions
### 1. Reactive vs Snapshot boundaries
- Reactive state belongs in VM flows (`graph`, app lists, favorites).
- Menus are snapshot-at-open:
  - bottom sheet actions are built once when opened
  - popup entries are built once when opened
- Do not make open menus auto-reactive.

### 2. Gesture closures must be keyed
- Never use `pointerInput(Unit)` for item-dependent row actions.
- Key by current data (`pointerInput(item, parent)`) to avoid stale captures.

### 3. Tag model invariant
- `LauncherItem.Tag.representative` is non-null.
- Empty/recursive/missing states must be represented via `LauncherItem.Placeholder`.
- Launch resolution should be iterative and fail loud if invariants break.

### 4. Popups and shortcuts
- Shortcut data can be missing from reactive cache for All Apps items.
- Use fallback query to `LauncherApps.getShortcuts(...)` on cache miss/empty.
- Memoize fallback results per package and invalidate on shortcut/package change.

### 5. Favorites and ordering
- `tag_items` primary key is `(tagId, itemOrder)`.
- Always allocate new order via `nextOrderForTag(tagId)` (`MAX(itemOrder)+1`).
- Never rely on `list.size` for order insertion.

### 6. DB policy (current alpha phase)
- Schema churn is allowed.
- Database uses destructive migration fallback.
- Keep schema/versioning simple until project exits alpha.

## Navigation/VM coupling
- Keep VM as source of action logic.
- Keep UI state ownership in UI/ViewVM.
- Use explicit callbacks over broad generic navigation interfaces when only one destination is needed.

## Testing Guidance
- Pure logic (resolvers, action builders) should have JVM tests.
- Android/system API behavior should be validated with manual device checks

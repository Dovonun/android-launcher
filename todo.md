# Cleanup Branch Checklist

This checklist is for iterative cleanup on the current cleanup branch.
Focus on simplifying code, clarifying reactivity boundaries, and removing dead/unnecessary complexity.

## 1. Reactivity Boundaries

- [x] Document which data must be reactive vs snapshot-only.
- [x] Keep tag graph reactive (`AppsVM.graph`).
- [x] Keep main app/favorites lists reactive (`uiAllGrouped`, `favorites`).
- [x] Ensure bottom sheet action building is snapshot-style (no live reactive updates while open).
- [x] Ensure popup entries are snapshot-style (computed once when opened).
  - Implemented: gesture handlers now key on `item,parent` to avoid stale closure captures.
  - Implemented: app popup entries use cache-miss fallback query and memoize fetched shortcuts.
- [x] Rename/refactor functions to make reactive vs snapshot intent explicit.
  - `sheetActionsSnapshot(...)` and `popupEntriesSnapshot(...)`.

## 2. Bottom Sheet Simplification

- [x] Keep `AppsVM` as single source of sheet actions.
- [x] Verify no duplicated UI-side action definitions in `ContextSheet`.
- [x] Keep action order and gating logic explicit and easy to read.
- [x] Verify terminal-item logic for app actions (`App Settings`, `Uninstall`) is correct.
- [x] Add concise comments only where logic is non-obvious.

## 3. Manage Screen Usability

- [x] Confirm current reorder controls are visible and usable.
  - Verified on device after explicit icon button colors.
- [x] Stop reusing fully interactive `IconRow` inside manage mode.
- [x] Split row into reusable layout-only component + behavior wrapper.
- [x] Use layout-only rows in `ManageTagScreen`.
- [ ] Keep reorder functionality working after refactor.
- [ ] Leave drag-and-drop as a follow-up (post-cleanup) unless it is small and low-risk.

## 4. Data Layer Hygiene

- [x] Remove obvious leftover/debug/test artifacts (e.g. temporary entities/comments).
- [x] Review DAO API surface for redundancy and document intent where both sync and reactive variants are needed.
- [x] Ensure naming is consistent (`TagItem`, `itemOrder`, etc.).
- [x] Verify no dead migrations/converters/entities remain.
  - Implemented: DB reset to v1 with destructive migration fallback for alpha iteration.

## 5. Favorites/Pinned Ordering Robustness

- [x] Use a shared helper for next order (`max(itemOrder) + 1`) for all append operations.
- [x] Apply same ordering strategy to pinned shortcut insertion.
- [ ] Verify add/remove sequences do not overwrite unrelated entries.
- [ ] Verify mixed entries (App + Tag + Shortcut) in favorites are stable.

## 6. Refresh Behavior Verification

- [ ] Verify app list refreshes after uninstall.
  - Implemented: uninstall path now triggers short refresh polling after delete intent.
- [ ] Verify package add/remove/change callbacks update UI correctly.
  - Implemented: package callbacks now trigger both app list refresh and shortcut cache refresh.
- [x] Review shortcut change callback path (`cleanup(pkg)`) and either implement or remove TODO path.
- [ ] Confirm no stale shortcut icons/labels after app/shortcut updates.

## 7. Converter and Schema Explanations

- [x] Add short explanation comments/docs for Room `TypeConverter` usage.
- [x] Explain why converter exists for `TagItemType` enum persistence.
- [x] Document storage format expectation (enum name string) and migration implications.
- [x] Keep explanations concise and local to where new contributors will look first.

## 8. Remove What We Don’t Need

- [x] Delete unused imports, dead code paths, and stale comments.
- [x] Prefer removing unnecessary abstractions over adding more wrappers.
- [x] Re-check each helper: keep only if it improves clarity or isolates side effects.
- [ ] Ensure “no line is best line” principle is applied where safe.

## 9. Test and Validation Pass

- [x] Compile check:
  - `JAVA_HOME=/snap/android-studio/current/jbr ./gradlew :app:compileDebugKotlin -q`
- [ ] Manual test: bottom sheet action matrix.
- [ ] Manual test: popup behavior and parent context handling.
- [ ] Manual test: favorites toggle/add/remove edge cases.
- [ ] Manual test: manage screen reorder flow.
- [ ] Manual test: uninstall refreshes all-apps view.

## 10. Optional Follow-up (Separate Branch)

- [ ] Implement drag-and-drop reorder in manage screen.
- [ ] Add focused tests around drag reorder persistence.
- [ ] Further UI decomposition if it still improves clarity after cleanup.

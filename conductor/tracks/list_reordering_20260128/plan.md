# Plan: List Reordering & Custom Popups

This plan implements universal list reordering and custom recursive popups using a unified database schema.

## Phase 1: Database Refactoring [checkpoint: 5b7a63f]
Consolidate the current tagging entities into a single, ordered `TagItemEntity` and update DAOs.

- [x] Task: Create `TagItemEntity` and update `AppDatabase` schema (replace `TaggedAppEntity` and `TaggedShortcutEntity`). f0a9ea8
- [x] Task: Update `TagDao` and implement `TagItemDao` with atomic ordering logic (single transaction for reorders). f49090e
- [x] Task: Create a migration utility in `AppsVM` to move existing Favorite/Pinned data to the new `TagItemEntity` structure. a8834b7 [Note: Switched to destructive migration 119f040]
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: ViewModel & Logic Updates
Update `AppsVM` to handle the new unified list resolution and index 0 "Representative" logic.

- [x] Task: Update `AppsVM.uiList` to fetch from `TagItemEntity` and sort such that `order = 0` is the "Representative". 016ae64
- [x] Task: Implement the "Representative" inheritance: A `TAG` item's label/icon/launch action comes from its child with `order = 0`. 0179c79
- [~] Task: Update `popupEntries` to exclude the representative (index 0) and trigger the "No shortcuts" snackbar if no other children exist.
- [ ] Task: Implement `updateOrder(tagId: Long, newOrder: List<Long>)` for atomic database updates.
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Reordering UI (Manage Tag View)
Create the drag-and-drop interface for reordering lists.

- [ ] Task: Create `ManageTagScreen` using Compose `LazyColumn` with drag-and-drop support (Index 0 at the bottom).
- [ ] Task: Implement navigation in `ViewVM` for `ManageTag` and add the round "Checkmark" confirmation button.
- [ ] Task: Ensure "Edit Popup" is hidden in the `AllApps` view context (pass context/flag to the sheet entries).
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Integration & Polish
Wire everything together and ensure recursive nesting works.

- [ ] Task: Implement the "Edit Popup" logic: If no custom tag exists, create one from current system shortcuts before opening the editor.
- [ ] Task: Update the Bottom Sheet entries ("Edit Favorites", "Edit Popup") to use the new navigation.
- [ ] Task: Final verification of the "Index 0 at bottom" layout and recursive swipe-to-open logic.
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)

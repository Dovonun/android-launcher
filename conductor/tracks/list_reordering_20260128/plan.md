# Plan: List Reordering & Custom Popups

This plan implements universal list reordering and custom recursive popups using a unified database schema.

## Phase 1: Database Refactoring [checkpoint: 5b7a63f]
Consolidate the current tagging entities into a single, ordered `TagItemEntity` and update DAOs.

- [x] Task: Create `TagItemEntity` and update `AppDatabase` schema (replace `TaggedAppEntity` and `TaggedShortcutEntity`). f0a9ea8
- [x] Task: Update `TagDao` and implement `TagItemDao` with atomic ordering logic (single transaction for reorders). f49090e
- [x] Task: Create a migration utility in `AppsVM` to move existing Favorite/Pinned data to the new `TagItemEntity` structure. a8834b7 [Note: Switched to destructive migration 119f040]
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) 5b7a63f

## Phase 2: ViewModel & Logic Updates [checkpoint: cd5a34d]
Update `AppsVM` to handle the new unified list resolution and index 0 "Representative" logic.

- [x] Task: Update `AppsVM.uiList` to fetch from `TagItemEntity` and sort such that `order = 0` is the "Representative". 016ae64
- [x] Task: Implement the "Representative" inheritance: A `TAG` item's label/icon/launch action comes from its child with `order = 0`. 0179c79
- [x] Task: Update `popupEntries` to exclude the representative (index 0) and trigger the "No shortcuts" snackbar if no other children exist. 2799077
- [x] Task: Implement `updateOrder(tagId: Long, newOrder: List<Long>)` for atomic database updates. 5081401
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) cd5a34d

## Phase 3: Reordering UI (Manage Tag View) [checkpoint: 065105c]
Create the drag-and-drop interface for reordering lists.

- [x] Task: Create `ManageTagScreen` using Compose `LazyColumn` with drag-and-drop support (Index 0 at the bottom). f15a7d2
- [x] Task: Implement navigation in `ViewVM` for `ManageTag` and add the round "Checkmark" confirmation button. 53c96e6
- [x] Task: Ensure "Edit Popup" is hidden in the `AllApps` view context (pass context/flag to the sheet entries). 92ac212
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) 065105c

## Phase 4: Integration & Polish [checkpoint: bc3edec]
Wire everything together and ensure recursive nesting works.

- [x] Task: Implement the "Edit Popup" logic: If no custom tag exists, create one from current system shortcuts before opening the editor. e5467ca
- [x] Task: Update the Bottom Sheet entries ("Edit Favorites", "Edit Popup") to use the new navigation. f4653a3
- [x] Task: Final verification of the "Index 0 at bottom" layout and recursive swipe-to-open logic. 97bfd09
- [x] Task: Fix ManageTag layout (use reverseLayout=true), hide LetterBar in non-AllApps views, and implement sheetEntries for Tag items. c258e27
- [x] Task: Restore LetterBar in Favorites, update Favorites view to be bottom-up (LazyColumn), and refine Tag sheet entries (Edit Tag, Remove from Favorites, Delete Tag). df50860
- [x] Task: Update `TagItemEntity` with a second Foreign Key for `targetTagId` with CASCADE delete. df50860
- [x] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md) bc3edec

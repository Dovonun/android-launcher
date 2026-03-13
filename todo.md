# Tag Add/Select Flow

- [x] Add `View.ManageTagAdd(tag, items)` and route it in `MainActivity`.
- [x] Build selector composable (in `ManageTagScreen.kt`) with:
  - [x] `#` tag section at top (tag name + `#`, no swipe).
  - [x] App sections grouped by letter.
  - [x] Checkmarks for selected items.
- [x] Add selector letter bar with `#` entry for tags + A-Z entries for apps.
- [x] Add shortcut popup for selector (swipe app row to toggle shortcuts).
- [x] Add `AppsVM.ensureItemsInTag(tagId, items)` to insert missing items with `nextOrderForTag`.
- [x] In `ManageTagScreen`, call `ensureItemsInTag` when returning from selector.
- [x] Add top "Add items…" row in `ManageTagScreen` to open selector.
- [ ] Manual checks:
  - [ ] Open selector, verify existing items are checked.
  - [ ] Add app + shortcut + tag, confirm/back returns to manage with appended items.
  - [ ] Letter bar `#` jumps to tag section; letters jump to app sections.

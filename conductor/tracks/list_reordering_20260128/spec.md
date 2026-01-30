# Specification: List Reordering & Custom Popups

## Overview
This track introduces universal list reordering and the ability to create custom "Popups" (folders/groups) that can replace default app shortcuts. It unifies how apps, shortcuts, and tags are handled in lists using a single, ordered database entity.

## Functional Requirements

### 1. Reordering Interface (Manage Tag View)
- **Entry Point:** 
    - "Edit Favorites" for items in the Favorites view.
    - "Edit Popup" for any app, shortcut, or tag in a custom list.
    - **CRITICAL:** The "Edit Popup" option is **hidden** for all items (and their popups) when viewed within the "All Apps" view.
- **Visuals:** 
    - A simple list view styled like the Favorites screen.
    - Rows include a drag indicator. Launch/long-press actions are disabled.
    - **Order Display:** Index 0 is displayed at the **bottom** of the list.
    - A round confirmation button with a checkmark at the bottom.
- **Interaction:**
    - Drag-and-drop reordering.
    - **Immediate Persistence:** Every successful drop recalculates and updates the `order` for ALL items in that specific list in one transaction.
- **Exit Behavior:** Exit via checkmark, system back, or home button.

### 2. Custom Popups & Index 0 Logic
- **Behavior:** Swiping an item in a list opens its "Popup".
- **The "Representative" (Index 0):**
    - The item with `order = 0` in a Tag's list is the "Representative".
    - The Parent Row (the Tag itself) inherits its **Icon**, **Label**, and **Launch Action** (on tap) from this item.
- **Popup Content:**
    - When the Popup is opened (swipe), it displays all items in the Tag **except** for the item at `order = 0`.
    - **Empty State:** If the Tag contains only one item (index 0), swiping shows the default "No shortcuts" snackbar (since there are no *additional* entries to show).
- **Recursion:** This logic applies to Tags nested within other Tags.

### 3. Simplified Database Schema
- **Unified Entity:** `TagItemEntity` replaces `TaggedAppEntity` and `TaggedShortcutEntity`.
- **Schema:**
    - `tagId`: Long (FK to `TagEntity.id`).
    - `order`: Int (0, 1, 2...).
    - `type`: Enum (APP, SHORTCUT, TAG).
    - `packageName`: String?
    - `shortcutId`: String?
    - `targetTagId`: Long? (The ID of the Tag this item points to).
    - `labelOverride`: String?
- **Constraints:** A composite unique index on `(tagId, order)` ensures sequential integrity.

## Acceptance Criteria
- [ ] "Edit Popup" is correctly hidden in All Apps view.
- [ ] Reorder view displays index 0 at the bottom.
- [ ] A Tag row correctly mirrors its index 0 child (Icon/Label/Launch).
- [ ] Swiping a Tag shows all children *except* index 0.
- [ ] Swiping a Tag with only 1 child shows the "No shortcuts" snackbar.
- [ ] Drag-and-drop updates the `order` for the entire list in the DB.
- [ ] Navigating back or home from the reorder view preserves the changes.

# Tag System Specification

## Overview
Tags are the primary organizational unit in the launcher. They allow items (Apps, Shortcuts, and other Tags) to be grouped, ordered, and displayed. A single item can belong to multiple tags.

## Core Concepts

### 1. Tag
- A Tag is a named collection of items.
- It is fully abstracted from the UI (no "folder" icons by default, unless it's a nested tag).
- **Representation:** A tag is represented in the UI by its first item (index 0). Launching the tag launches its first item. Swiping the tag opens a popup containing the rest of the items (index 1 and onwards).

### 2. Items
A tag can contain:
- **App:** A reference to an installed Android application (Package Name).
- **Shortcut:** A reference to an Android shortcut (Package Name + Shortcut ID).
- **Tag (Nested):** A reference to another Tag. This allows for recursion and nested popups.

### 3. Special Tags
- **Favorites (ID: 1):** Populates the main view.
- **Pinned (ID: 2):** Shortcuts created via `pinShortcutRequest`. Shown in the all-apps list (treating PWAs like native apps).
- **Hidden:** (Reserved for future) Hides tagged elements from the all-apps view.

### 4. Custom Tags (Popup Overrides)
- Any item in a tag can be "upgraded" to a custom tag.
- This creates a new Tag, moves the original item to index 0 of that new Tag, and populates the rest with defaults (e.g., system shortcuts for that app).
- The original reference in the parent tag is replaced by a reference to this new Tag.

## Technical Specification

### 1. Database Schema
We use a unified "Tag Item" model. This table acts as a simple mapping of "Slots" in a list.

#### `tags` table
- `id` (Long, PK)
- `name` (String)

#### `tag_items` table
- `tagId` (Long, FK -> tags.id, PK part 1)
- `itemOrder` (Int, PK part 2)
- `type` (Enum: APP, SHORTCUT, TAG)
- `packageName` (String?, nullable)
- `shortcutId` (String?, nullable)
- `targetTagId` (Long?, FK -> tags.id, nullable)
- `labelOverride` (String?, nullable)

### 2. In-Memory Graph Architecture
To ensure zero-latency UI interactions, the entire tag system is loaded into an in-memory graph upon startup or DB change.

#### Data Structure (`LauncherItem` Sealed Interface)
```kotlin
sealed interface LauncherItem {
    val label: String
    val icon: ImageBitmap?

    data class App(...) : LauncherItem
    data class Shortcut(...) : LauncherItem
    data class Tag(
        val id: Long,
        val name: String,
        val items: List<LauncherItem>,
        var representative: LauncherItem?
    ) : LauncherItem
}
```

#### Two-Pass Construction
1.  **Pass 1 (Graph Building):** 
    - Load all `tags` and `tag_items`.
    - Create `Tag` objects for every tag.
    - Populate `items` for each tag. If an item is a `TAG` type, point to the corresponding `Tag` object instance. This allows for circular references.
2.  **Pass 2 (Representative Resolution):**
    - For each `Tag` object, find its "Index 0" representative.
    - Use a `visited: Set<Long>` to track recursion.
    - **Logic:**
        - If `items[0]` is an App/Shortcut, that is the representative.
        - If `items[0]` is a Tag, recurse into that tag's `items[0]`.
        - If `items` is empty, representative is `null`.
        - If a cycle is detected, representative is a special "Recursion Easter Egg" item.

### 3. UI Behavior
- **Empty Tags:** If a user launches/swipes an empty tag, show the tag name and a Toast: "This tag is empty."
- **Recursion Easter Egg:** If a loop is detected during resolution, display a unique "Infinity" icon.
- **Context Menus:** Use the graph's parent-child relationships to provide context-aware actions (e.g., "Remove from [Parent Tag Name]").

### 3. Open Questions & Decisions
- **Custom Icons/Names:** The `labelOverride` in `tag_items` handles custom names. Custom icons can be added similarly (e.g., `iconUriOverride`).
- **Tests:** While manual testing is essential, unit tests for the recursive resolution logic are recommended to prevent regressions in complex nested structures.
- **Minimalism:** `Converters.kt` is kept as necessary boilerplate for Room Enum support.

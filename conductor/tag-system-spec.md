# Tag System Specification

## Overview
Tags are the primary organizational unit in the launcher. They allow items (Apps, Shortcuts, and other Tags) to be grouped, ordered, and displayed. A single item can belong to multiple tags.

## Core Concepts

### 1. Tag
- A Tag is a named collection of items.
- **Representation:** A tag is represented in the UI by its first item (index 0). Launching the tag launches its first item. Swiping the tag opens a popup containing the rest of the items (index 1 and onwards).

### 2. Items
A tag can contain:
- **App:** A reference to an installed Android application (Package Name).
- **Shortcut:** A reference to an Android shortcut (Package Name + Shortcut ID).
- **Tag (Nested):** A reference to another Tag. This allows for recursion and nested popups.

### 3. Special Tags
- **Favorites (ID: 1):** Populates the main view.
- **Pinned (ID: 2):** Shortcuts created via `pinShortcutRequest`. Shown in the all-apps list.

## Technical Specification

### 1. Database Schema
We use a unified "Tag Item" model. This table acts as a simple mapping of "Slots" in a list.

#### `tag_items` table
- `tagId` (Long, FK -> tags.id, PK part 1)
- `itemOrder` (Int, PK part 2)
- `type` (Enum: APP, SHORTCUT, TAG)
- `packageName`, `shortcutId`, `targetTagId` (Nullable fields for the item reference)
- `labelOverride` (String?, nullable)

### 2. In-Memory Graph Architecture
The entire tag system is loaded into an in-memory graph upon startup or DB change to ensure zero-latency UI.

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
        private val getItems: () -> List<LauncherItem>, // Lazy provider
        val representative: LauncherItem? // Memoized face
    ) : LauncherItem {
        val items: List<LauncherItem> by lazy { getItems() }
    }
    data class Recursion(...) : LauncherItem // Easter egg for infinite loops
}
```

#### Order Convention (Crucial)
- **VM/Database Order:** Items are stored and exposed in **Canonical Order** (Index 0 = Representative).
- **UI Rendering:** All lists (Favorites, Popups, ManageTag) are rendered **Bottom-Up**.
    - In `MainActivity`: `favorites.asReversed().forEach`.
    - In `LazyColumn`: `reverseLayout = true`.
    - Result: Index 0 is always at the bottom of the screen.

### 3. UI Behavior & Context Menus
- **Additive Sheets:** Context menus are parent-aware. If an item is inside a tag, the menu shows BOTH the container's management options ("Manage [Tag Name]", "Remove from [Tag Name]") and the item's own options ("Open Settings", "Uninstall").
- **Empty Tags:** Triggers a "This tag is empty" toast on launch.
- **Recursion:** Loops are detected during resolution. The tag displays an "∞" icon. Launching a recursion loop triggers an "Infinite loop detected!" toast.

### 4. Technical Constants
- **`TAG.FAV = 1`**: Reserved ID for the favorites list.
- **`TAG.PINNED = 2`**: Reserved ID for pinned shortcuts.
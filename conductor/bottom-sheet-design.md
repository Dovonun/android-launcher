# Bottom Sheet Redesign Specification

## Overview
The bottom sheet is the primary context-sensitive menu for the launcher. It should provide a clean, unified interface for managing items (Apps, Shortcuts, Tags) and their containers.

## Visual Design

### 1. Header
- **Icon**: Large icon of the representative item.
- **Label**: Name of the representative item (or the Tag name if it's a placeholder).
- **Tag Badges**: Small badges displayed next to/behind the name, representing all tags that contain this specific App or Shortcut (Global lookup).

### 2. Action List
- A single, flat list of actions (no sub-sections).
- Dividers may be used sparingly if it improves clarity, but the goal is a cohesive list.

## Action Logic (Parent-Aware)

### Context Definitions
- **Item**: The object the user long-pressed (could be an App, Shortcut, or Tag).
- **Representative**: The App/Shortcut resolved from the item (if the item is a Tag).
- **Parent**: The container the user was looking at when they opened the menu (e.g., Favorites or a Tag popup).

### Rules for Actions

#### Container Management (if Parent exists)
- **Manage [Parent]**: Opens the Tag Editor for the parent.
- **Remove from [Parent]**: Removes the *Item* from the parent container.

#### Item Management
- **Manage [Item]**: (If item is a Tag) Opens the Tag Editor for the item.
- **App Settings / Uninstall**: (If representative is an App) Always shown.
- **Add to/Remove from Favorites**: (Global toggle) Shown only if the item is NOT currently a Tag representative.
- **Create Tag**: (If representative is an App) Shown only if the item is NOT a Tag representative.

## Decision Tree Matrix

| Context | Item Type | Actions |
| :--- | :--- | :--- |
| All Apps | App | Add/Remove Fav, Create Tag, App Settings, Uninstall |
| Favorites | App | Remove from Favorites, Manage Favorites, Create Tag, App Settings, Uninstall |
| Favorites | Tag | Remove Tag from Favorites, Manage Favorites, Manage Tag, App Settings (Rep), Uninstall (Rep) |
| Popup (Tag A) | App | Remove from Tag A, Manage Tag A, App Settings, Uninstall |
| Popup (Tag A) | Shortcut | Add to Fav (Shortcut), Remove from Tag A, Manage Tag A |

## Implementation Details
- `AppsVM` will provide a `getTagsForItem(item)` helper to resolve badges.
- `MenuState.Sheet` carries the `(item, parent)` context.
- `sheetEntries` follows the flat list pattern with context-relative removal logic.

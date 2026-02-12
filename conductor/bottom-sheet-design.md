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

### Rules for Actions (Ordered)

1.  **Remove from [Parent]**: Removes the *Item* from the parent container. (Shown only if Parent exists).
2.  **Manage [Item]**: Opens the Tag Editor for the item (Shown only if item is a Tag).
3.  **Manage [Parent]**: Opens the Tag Editor for the parent. (Shown only if Parent exists).
4.  **Add to / Remove from Favorites**: Global toggle for the *representative*.
    - **HIDDEN** if the item is a Tag (to prevent duplicate removal options and keep focus on the container).
5.  **Create Tag**: Creates a new Tag pre-filled with the app and its shortcuts.
    - **HIDDEN** if the item is a Tag.
6.  **App Settings**: (If representative is an App/Shortcut) Always shown.
7.  **Uninstall**: (If representative is an App) Always shown.

## Decision Tree Matrix

| Context | Item Type | Actions (In Order) |
| :--- | :--- | :--- |
| All Apps | App | Add/Remove Fav (Global), Create Tag, App Settings, Uninstall |
| Favorites | App | Remove from Favorites, Manage Favorites, Create Tag, App Settings, Uninstall |
| Favorites | Tag | Remove from Favorites, Manage [Tag], Manage Favorites, App Settings (Rep), Uninstall (Rep) |
| Popup (Tag A) | App | Remove from Tag A, Manage Tag A, App Settings, Uninstall |
| Popup (Tag A) | Shortcut | Remove from Tag A, Manage Tag A, App Settings (Rep) |

## Implementation Details
- `AppsVM` will provide a `getTagsForItem(item)` helper to resolve badges.
- `MenuState.Sheet` carries the `(item, parent)` context.
- `sheetEntries` follows the flat list pattern with context-relative removal logic.

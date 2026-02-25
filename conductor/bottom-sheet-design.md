# Bottom Sheet Redesign Specification

## Overview
The bottom sheet is the primary context-sensitive menu for the launcher. It provides one flat list of actions for Apps, Shortcuts, and Tags.

## Visual Design

### 1. Header
- **Tag item**:
  - **Icon**: None.
  - **Label**: Tag name (`tag.name`).
  - **Tag Badges**: Tags that contain this Tag item.
- **Non-tag item**:
  - **Icon**: Item icon.
  - **Label**: Item label.
  - **Tag Badges**: Tags that contain this item.

### 2. Action List
- A single flat list of actions.
- No header/divider entries are emitted by the decision engine.

## Action Logic (Parent-Aware)

### Context Definitions
- **Item**: The object the user long-pressed (App, Shortcut, or Tag).
- **Parent**: The container the user was viewing when they opened the sheet (Favorites or a Tag popup).
- **Terminal item**: Resolved non-Tag item after walking Tag representatives.

### Decision Tree (Ordered)
1. **Has parent?**
- **No**: check membership in Favorites and show exactly one:
  - `Add to Favorites` when item is not in Favorites
  - `Remove from Favorites` when item is already in Favorites
- **Yes**: show `Remove from <Parent>`, `Manage <Parent>`

2. **Is item a Tag?**
- **Yes**: show `Manage <Tag>`
- **No**: show `Create Tag`

3. **Is terminal item an App?**
- **Yes**: show `App Settings`, `Uninstall`
- **No**: show no app-level system actions

### Canonical Flow (ASCII)
```text
has parent?
├─ no  -> Add/Remove Favorites (membership toggle)
└─ yes -> Remove from <Parent>, Manage <Parent>

is tag?
├─ no  -> Create Tag
└─ yes -> Manage <Tag>

terminal item is app?
├─ yes -> App Settings, Uninstall
└─ no  -> (none)
```

## Placeholder Behavior
- Tag representatives are never null.
- Placeholder representatives are used for:
  - Empty tag
  - Recursive tag reference
  - Missing/dangling reference
- Placeholder terminals do not get app-level system actions.

## Implementation Details
- `AppsVM` provides `getTagsForItem(item)` for badges.
- `MenuState.Sheet` carries `(item, parent)` context.
- `sheetEntries` in `AppsVM` is the single source of action decisions.

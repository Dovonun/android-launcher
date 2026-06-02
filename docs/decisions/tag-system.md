# Tag System

Tags are the launcher's main organization model. A tag can contain apps, shortcuts, or other tags.

## Special tags
- **Favorites:** main launcher view.
- **Pinned:** shortcuts created through Android pin shortcut requests.

## Representation
A tag is represented by its first item. Launching a tag launches that representative. Swiping/opening the tag shows the rest of its items.

Empty, recursive, or missing references should be displayed as placeholders instead of breaking the UI.

## Ordering
Tag items have a stable order. The first item is the representative.

Lists render bottom-up so the first item appears at the bottom of the screen. This keeps the main action near the user's thumb and makes tags feel like they open upward.

## Storage
`tag_items` maps ordered slots inside a tag:

- `tagId`
- `itemOrder`
- item reference fields for app, shortcut, or nested tag
- optional label override

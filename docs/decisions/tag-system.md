# Tag System

Tags are the launcher's main organization model. A tag can contain apps, shortcuts, or other tags.

## Special tags
- **Favorites:** main launcher view.
- **Pinned:** shortcuts created through Android pin shortcut requests.
- **Hidden:** persistent shortcut annotations; rendering does not filter them yet.

## Representation
A tag is represented by its first item. Launching a tag launches that representative. Swiping/opening the tag shows the rest of its items.

Empty, recursive, or missing references should be displayed as placeholders instead of breaking the UI.

## Ordering
Tag items have a stable order. The first item is the representative.

Lists render bottom-up so the first item appears at the bottom of the screen. This keeps the main action near the user's thumb and makes tags feel like they open upward.

## Android shortcuts

The graph resolves app popup shortcuts and tag items together, including their label overrides.
App popups keep managed shortcuts first: Pinned tag rows take precedence, then other tags by ID,
with each tag's rows in `itemOrder`. An explicit tag keeps its own order and labels.

Shortcuts not yet managed are appended. Unsaved static actions precede dynamic actions, each by
Android rank; unsaved pinned-only shortcuts retain the platform query order because Android
provides no creation order for them. Their initial relative order is not a promise.

The Pinned tag also displays Android-pinned shortcuts with no stored row. Manage uses the visible
list, so saving writes those entries and their chosen `itemOrder` through the normal tag save path.
Later platform queries cannot reorder that saved prefix; newly discovered entries append to it.
Transient entries are not written merely because the graph queried Android.

Android's regular launcher API exposes pins belonging to this launcher, not pins owned exclusively
by another launcher. Pin requests are recorded in a transaction before the receiving activity finishes.

## Shortcut removal (alpha)

Manage swipe deletion and the context sheet share the same removal flow:

- Pinned-only shortcuts offer **Unpin**, **Hide**, or **Cancel**. Unpin replaces Android's pin set
  for the package while retaining every other pin, then removes all stored references to that
  shortcut across tags. The dialog explicitly describes the global removal.
- Static/dynamic actions are marked Hidden immediately, including actions that are also pinned.
  Unpinning these would not stop the publisher from exposing them.
- Hide adds one `(package, shortcutId)` entry to Hidden and removes the current tag membership.
  Other tag memberships and Android pins remain. Hidden is annotation only in this pass: entries
  can still appear, including through Pinned discovery. Removing an entry from Hidden clears the
  annotation without unpinning it.

Hidden uses reserved ID `-1`, avoiding existing positive user-tag IDs; it cannot be renamed or
deleted. Its entries do not contribute app-popup ordering or label precedence. Filtering and
visibility rules are a follow-up, not part of this patch.

Pin acceptance and unpin/hide mutations share an in-process mutex. Database changes use a Room
transaction; permission failures leave local memberships intact. Android and Room cannot commit
atomically: if cleanup fails after Android unpins, report the error; database cleanup must be retried.

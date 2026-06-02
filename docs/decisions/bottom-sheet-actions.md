# Bottom Sheet Actions

The bottom sheet is the context menu for apps, shortcuts, and tags.

## Design
- One flat list of actions.
- Header shows the selected item and related tag badges.
- Actions are based on the selected item and the parent container, if any.

## Action order
1. If opened outside a tag, show the Favorites add/remove action.
2. If opened inside a tag, show remove/manage actions for that parent tag.
3. If the selected item is a tag, show manage-tag.
4. If the selected item is not a tag, show create-tag.
5. If the resolved target is an app, show app settings and uninstall.

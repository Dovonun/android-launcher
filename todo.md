# Tag Manager Plan (Work Tracker)

## Summary
Add a Tag Manager list opened from tag badges in the bottom sheet. The list supports inline rename on tap, horizontal-drag content preview popup (read-only + Manage Tag action), and long-press delete with confirmation. Favorites/Pinned are locked from rename/delete.

## Progress
- [x] Implement View state + navigation entry point (tag badge opens Tag Manager).
- [x] Tag Manager list UI (snapshot at open): rows show tag name, lock system tags.
- [x] Inline rename on tap (edit state + confirm/cancel).
- [x] Horizontal drag opens preview popup (read-only list + “Manage Tag” action).
- [x] Long-press delete confirm dialog (blocked for system tags).
- [x] AppsVM: rename + delete helpers using TagDao.
- [x] Wire Manage Tag navigation from popup.
- [ ] Manual checks (see below).
- [x] Build: `:app:compileDebugKotlin`.

## Manual Checks
- [ ] Tap tag badge opens Tag Manager list.
- [ ] Tap row enters rename; confirm updates tag name everywhere.
- [ ] Horizontal drag opens preview popup; items are read-only.
- [ ] Popup “Manage Tag” navigates to existing Manage screen.
- [ ] Long-press shows delete dialog; delete removes tag and references.
- [ ] Favorites/Pinned cannot rename/delete.

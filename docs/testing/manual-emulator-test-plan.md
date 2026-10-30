# Manual emulator test plan

Short manual pass for changes that touch the home screen, All Apps, tags, popups or persistence.
The automated subset lives in `e2e/` (see "Automated coverage" below); run it first, then do the
steps it does not cover.

## Emulator setup

- AVD: Pixel profile, API 30 (Android 11) Google APIs x86, 1080x1920, 420 dpi. API 30 is the
  app's `minSdk`; Google APIs images ship Chrome, Clock and Settings, which these steps use.
- Without hardware acceleration (no KVM, e.g. in a VM), start it headless and software-only:

  ```sh
  emulator -avd <avd> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -accel off -memory 2560 -no-snapshot -no-metrics
  adb wait-for-device; adb shell getprop sys.boot_completed   # 1 when booted
  ```

  Boot takes ~10 minutes and the first touches often raise "isn't responding" dialogs; tap
  **Wait**. Those ANRs come from emulation, not the app.
- Build an x86-capable dev APK (the default `abiFilters` is arm64 only), install, set as home:

  ```sh
  ./gradlew assembleDevDebug        # add x86 to abiFilters locally for x86 images
  adb install -r app/build/outputs/apk/dev/debug/ni-launcher-dev-debug.apk
  adb shell cmd package set-home-activity com.ni.launcher.dev/com.ni.launcher.MainActivity
  adb shell input keyevent HOME
  ```

- Useful while testing:
  - Crashes: `adb logcat -b crash` and `adb logcat | grep -E "FATAL|ANR in"`.
  - Data (emulator `adbd` runs as root on Google APIs images):
    `adb shell "sqlite3 /data/data/com.ni.launcher.dev/databases/launcher.db 'select * from tag_items;'"`
  - Shortcuts an app publishes: `adb shell dumpsys shortcut` (manifest = `Man`, dynamic = `Dyn`,
    pinned = `Pin`).
  - Reset: `adb shell pm clear com.ni.launcher.dev`, then set home again.

## Steps

Each step lists the action, then the expected result.

### Home and favorites

1. Fresh data, press Home. -> Empty home, no crash. The right edge still reacts to touch.
2. Add three apps to Favorites (step 6). -> Rows are stacked from the bottom (lowest row about 1/8
   of the screen above the bottom), first-added at the bottom.
3. Add a fourth favorite. -> Existing rows keep their exact position; the new row appears on top.
   No row jumps or flickers while the screen loads (no layout shift).
4. Tap a favorite. -> The app opens. Press Home. -> Back on Favorites.
5. Swipe down on the home screen. -> Notification shade expands.

### All Apps and letter bar

6. Touch the right edge (letter bar area, lower two thirds). -> All Apps opens, letter bar
   expands and shows every first letter.
7. Press and slowly drag along the bar. -> The list jumps to each letter, the current letter is
   highlighted, haptic tick per letter. Drag above/below the bar. -> Returns to Favorites.
8. Long-press an app in All Apps. -> Context sheet: Add to Favorites, Create Tag, App Settings,
   Uninstall.
9. Tap **Add to Favorites**. -> Manage Favorites opens with the app added on top; tap the check
   button. -> Home shows it.
10. Launch an app from All Apps, then press Home within 5 s. -> All Apps is still shown (soft
    reset keeps it); after 5 s in the app, Home shows Favorites.

### Tags

11. Long-press an app with shortcuts (e.g. Clock) -> **Create Tag**. -> Tag "Clock Tag" is
    created with the app and its shortcuts, added to Favorites, and Manage Tag opens.
12. In Manage Tag, drag a row up/down (anywhere on the row, handle on the right). -> Rows swap as
    you pass half a row; order is kept after the check button and after reopening.
13. **Add items...** -> selector with its own letter bar; tap apps to select, swipe right on an
    app to pick its shortcuts, select another tag (`#` rows) to nest it. Confirm. -> Items are
    appended in selection order; the nested tag shows as a row.
14. Swipe a row left/right past ~30% in Manage Tag. -> Row is removed (delete icon turns red at
    the threshold). Below the threshold it snaps back.
15. On home, swipe right on the tag row. -> Popup shows the tag's items; swipe right on a nested
    tag inside it. -> Its items open, anchored at the same place.
16. Long-press any item -> tap the tag chip in the sheet header. -> Tag Manager opens; rename a tag
    (pencil), delete a non-system tag (confirm dialog). Favorites/Pinned show a lock and cannot
    be renamed or deleted.

### App popups and shortcut order

17. Swipe right on an app row (Clock: 4 dynamic shortcuts). -> Popup opens over the row, first
    entry next to the row, entries in shortcut rank order. Tap outside or press Back. -> Closes.
18. App with manifest + dynamic + pinned shortcuts (Chrome after first run: "New tab" manifest,
    "Incognito Tab" dynamic, plus pins from step 20). -> From the row upwards: manifest, then
    dynamic, then pinned/managed in their saved order. No duplicates.
19. With 5+ entries. -> Popup is capped (~4.7 rows), starts scrolled to the bottom, top edge
    fades; dragging inside scrolls to the remaining entries.

### Pinned shortcuts

20. In Chrome open a page, menu -> **Add to Home screen** -> Add (repeat with other pages). ->
    The pin dialog closes without a launcher screen staying open. The shortcut appears in All
    Apps under its first letter and in the Chrome popup, and a row exists with `tagId = 2`.
21. `adb shell am force-stop com.ni.launcher.dev`, press Home. -> Pins are still in All Apps and
    the popup.
22. A shortcut label saved by the launcher (`labelOverride`, written by the pin request and by
    Create Tag) is the label shown in the app popup too. There is no UI to rename a single
    shortcut yet; to check, change `labelOverride` with `sqlite3` while the launcher is
    force-stopped, then reopen the popup.

### Remove

23. Long-press a favorite -> **Remove from Favorites**. -> Row disappears, the remaining rows do
    not jump; it stays removed after a force-stop.
24. Long-press an item inside a tag popup -> **Remove from <tag>**. -> Removed from that tag only.

### App install, update, uninstall

25. Long-press an app -> **Uninstall** -> OK. -> It disappears from All Apps (within a few
    seconds) and from Favorites/tags without a crash.
26. `adb install -r` an app update (e.g. reinstall the dev APK of another flavor or any test
    APK). -> Launcher keeps working; the updated app's shortcuts refresh in its popup.
27. Install a new app (`adb install some.apk`). -> It appears in All Apps under its letter without
    restarting the launcher.

### Process death and persistence

28. After steps 2-23: `adb shell am force-stop com.ni.launcher.dev` (or `am kill` while in another
    app), press Home. -> Favorites, tags, order, pins and labels are unchanged.
29. Install a build over an older/newer schema (`adb install -r -d`). -> App opens (destructive
    migration may reset data; note it) and does not crash on DB open.

## Automated coverage

`./gradlew :e2e:connectedDebugAndroidTest` (UI Automator, see `e2e/`) covers steps 1, 6-7 (open
All Apps from the right edge and jump with the letter bar), 8-9 (add to Favorites via the
context sheet), 28 (survives force-stop) and 17 (swipe opens an app popup; Back closes it).
Everything else above, including popup ordering (18-19), pins (20-22), tags, drag reorder,
uninstall/update and layout-shift checks, stays manual.

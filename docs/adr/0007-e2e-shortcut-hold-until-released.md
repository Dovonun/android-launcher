# 0007 e2e: hold shortcut discovery until the test releases it

Status: accepted (v5), test infrastructure only (debuggable builds)

## Context
`LauncherE2ETest#manageTagBeforeShortcutsLoadKeepsSavedShortcuts` (e2e/.../LauncherE2ETest.kt:94-136)
cold-starts the launcher with `log.tag.NiE2EHoldShortcuts=DEBUG`, opens Manage on a tag while only
apps are known, and asserts at :123 that fewer rows show than after shortcuts load. The hook in
SystemSource (`e2eHoldShortcuts`) delayed stage 4 by a fixed 30 s.

On the TCG emulator (no KVM) the test's 300-step long-press alone takes 18-36 s, with ANRs and
500-1400 skipped frames, so reaching Manage often takes longer than 30 s. Result per version:
v2 3/3, v3 1/3, v3b 0/3, v4 0/3. Logcat shows the same thing in every run of every version: the
test passes if Manage was opened before `stage 4 shortcuts` was logged and fails if it was opened
after. The v4 failures (results-v4/e2e) all have "Manage Settings Tag" clicked after stage 4
(01:49:52 → 01:50:02, 02:28:35 → 02:29:06, 02:34:01 → 02:50:56, 03:04:18 → 03:04:37), with guest
stalls of 8-18 min between log lines. v2's passes had 1.6-2.2 s of margin.

The graph code is not involved: SystemSource is the same in d40c7d9, 18a0d57, 66a88ff and v5
before this change; Manage opens with `tagItems(id)` = `tagView.value.rowsByTag[id]` in all of
them, unresolved shortcut edges are dropped from rows in all of them, and nothing can publish
shortcuts before stage 4.

## Decision
While the property is set, stage 4 waits until the test clears it (polling `Log.isLoggable` every
250 ms, at most 10 min), instead of a fixed 30 s. The test already clears the property right after
its early check (`setprop log.tag.NiE2EHoldShortcuts ''`, LauncherE2ETest.kt:124) and `@Before`
clears it for every test, so the test needs no change. The log line still contains
"holding shortcut discovery", which the test checks.

## Consequences
- The race is gone however slow the device is; the test checks behavior, not emulator speed.
- Release builds are unaffected (the hook is gated on FLAG_DEBUGGABLE and the property).
- Unverified: that `Log.isLoggable` sees the cleared property in a running process without
  restart (liblog re-reads changed properties; to confirm on the emulator).
- Touches SystemSource (13-line diff, debug-only function), which was otherwise kept untouched.

## Alternatives
- Longer fixed hold (e.g. 120 s): still a race, makes every run slower.
- Shorter long-press in the test (UiObject2.longClick / fewer steps): reduces but doesn't remove the race.

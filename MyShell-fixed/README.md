# MyShell Island — native Android Dynamic Island

Kotlin · Jetpack Compose · Material 3 · min SDK 26 (Android 8) · target/compile SDK 35.
A floating black pill at the top of the screen that shows **real** notifications, **real** media sessions,
app shortcuts, clipboard history and system info — above every app.

## How it behaves

- **Idle:** a small black circle/pill wrapped around your front camera hole (or notch). Size is read from the
  display cut-out; phones without one get a small centred pill.
- **Activity:** it grows for a notification alert, for playing music (artwork + equaliser) or when opened.
- **Opening:** smooth spring animation; the overlay window is resized only between states, never per frame.
- **Notifications:** swipe a card **right** to delete it (also removed from the Android shade), **tap** to open
  the app, **long-press** for actions / reply.
- **Clock app timer / stopwatch:** while one is running, the idle island shows a live countdown (read from the
  Clock app's ongoing notification via notification access; Google, Samsung and most OEM clocks).
- **Charging:** plug in the charger and the island springs open with a green bolt, battery % and an animated
  battery, then settles into a small green bolt + battery % pill that stays for as long as the phone charges.
- **Silent / Vibrate / Ring:** flipping the sound mode briefly shows a wiggling bell / vibrate icon and the label.
- **Size and alignment** (Settings → Size and alignment): open width and height, idle width and height,
  active-pill width and height, horizontal and vertical position, plus *Open island* / *Preview alert* /
  *Charging* / *Silent* buttons to see changes live, and a reset button.
- **Customise every state** in the app (Settings → Island states): idle size, active pill width, charging and
  sound-mode on/off + duration, music pill, clock-timer pill.
- **Tabs:** Notifications, Music (only while a media session exists), Apps, Clipboard.
  Swipe left/right on empty areas or use the tab bar; swipe up or tap outside to close.

> **Build status:** built by GitHub Actions. This revision was edited without a compiler available, so if the
> build fails, paste the log and it will be fixed quickly.

## Build

**Android Studio (easiest):** File → Open → this folder → let Gradle sync → Run ▶ on a device.
Needs JDK 17 (bundled with recent Android Studio).

**Command line:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

**No local setup:** push this folder to a GitHub repo. `.github/workflows/build-apk.yml` builds the debug APK
and uploads it as the `MyShell-debug-apk` artifact (Actions tab → latest run).

## First run (on the phone)

1. **Display over other apps** → Grant.
2. **Notification access** → Grant, then switch *MyShell Island* on.
   *Installed from an APK on Android 13+ and the switch is greyed out?* Open **App info → ⋮ → Allow restricted
   settings**, then try again (the Home screen has a shortcut button).
3. Android 13+: allow **Notifications** (only used for the silent service notice).
4. Samsung One UI: Settings → Battery → Background usage limits → **Never sleeping apps** → add MyShell Island.

## Permissions — why each one exists

| Permission / access | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` (Display over other apps) | Draw the island (`TYPE_APPLICATION_OVERLAY`) above other apps. |
| Notification Listener access | The core feature: receive real notifications; also required by Android to list other apps' media sessions. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay alive while other apps are in front. Declared as `specialUse` (Android 14+ requires a type; there is no overlay-specific type). |
| `POST_NOTIFICATIONS` (Android 13+) | Show the silent "running" notice. |
| `RECEIVE_BOOT_COMPLETED` | Optional restart after reboot (setting: *Start on boot*). |
| *(none)* `QUERY_ALL_PACKAGES` | **Not** requested. Shortcuts use a `<queries>` LAUNCHER intent, which is enough to list launchable apps. |
| *(none)* battery-optimisation exemption | **Not** requested; the app only links to the settings page. |

## Android restrictions you will notice (and what the app does)

- **Clipboard**: Android 10+ only lets the *focused* app read the clipboard. MyShell listens while allowed and
  offers a **Capture** button that opens an invisible activity for a split second — the supported way to read it.
  Clips flagged sensitive by the source app are skipped.
- **Replies/actions**: only offered when the source notification provides them (`RemoteInput` / actions).
- **Lock screen / secure screens**: the system hides overlays there.
- **Privacy**: notifications marked *secret* (or *private* while locked) are stored without content; the
  *Hide message previews* setting blanks text everywhere. History and clips are stored locally (DataStore).
  Nothing is uploaded; the app has no INTERNET permission.

## Prototype → native mapping

| Prototype (HTML) | Native |
|---|---|
| Pill + expandable panel, spring animation | `IslandHost.kt` (Compose springs, `animateContentSize`), window via `OverlayService` |
| Swipe left/right between tabs, swipe down/up, tap outside | `ExpandedPanel` + `OverlayRoot` (`FLAG_WATCH_OUTSIDE_TOUCH`); children that scroll win over swipes |
| Music (simulated) | `MediaRepository` — real `MediaSessionManager` sessions, artwork, seek, prev/next |
| Apps row (add, reorder, long-press) | `AppsTab` + `ShortcutRepository` (pinned, order, recents) |
| — (new) | Notifications tab, clipboard history, Settings app, history screen |

**Not ported:** *Downloads* and *Shelf* tabs. Downloads was purely simulated and Android gives apps no API to
observe other apps' downloads; Shelf relied on browser drag-and-drop of files, which has no equivalent for a
system overlay. Tell me if you want a native Shelf (pick files with the system picker, share them out).

## Layout

```
app/src/main/java/com/myshell/island/
  MyShellApp.kt            Application + container accessor
  di/AppContainer.kt       manual dependency injection, icon cache
  data/                    DataStore repositories, models
  media/MediaRepository.kt MediaSession integration
  system/SystemMonitor.kt  battery and sound-mode events
  service/                 OverlayService, ShellNotificationListener, mapper, boot receiver
  ui/island/               the island: host, tabs, view-model, widgets
  ui/                      MainActivity (home, history, settings), ClipboardCaptureActivity
```

## Known risks (first build)

- Written without a compiler: small type/import mistakes are likely; they are quick to fix in Android Studio.
- `ComposeView` hosted in a `WindowManager` window (no Activity) is set up with manual lifecycle / saved-state /
  view-model owners in `OverlayService`. This is a known pattern but is the first place to look if the island
  doesn't appear.
- Inline reply needs the overlay window to become focusable (done by toggling `FLAG_NOT_FOCUSABLE`); keyboard
  behaviour differs per OEM, so test it on One UI.
- Not performance-profiled on a Galaxy M51; the design avoids background loops (push-based battery/sound-mode,
  samples only while a tab is visible), but this is unmeasured.

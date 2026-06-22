# Xmo Map Video Player

A tiny launcher app for the **Changan CS55 Plus Premium** head unit (MediaTek "alps", Android 9, 1920×720, rooted). It opens **Google Maps (or Waze) + YouTube ReVanced** side-by-side as two real freeform windows — full native performance, real apps, no embedded WebView nonsense.

Default layout: **Maps 25% (left, near driver) · YouTube 75% (right)**.

This is stage 1 of the feature set. Stage 2 (movable divider, fullscreen toggle for either side, persistent control bar) is architected for but not implemented yet — see [Stage 2](#stage-2-planned) below.

## How it works

Same approach the FOSS `Taskbar` app uses — **public Android APIs, no root required for the core flow.**

1. The app holds `WRITE_SECURE_SETTINGS` (granted once via `adb shell pm grant ...` — see setup below). With that, it writes `enable_freeform_support` and `force_resizable_activities` to `Settings.Global` from in-process. No `am` / `settings` shell-out.
2. For each target app, it builds an `Intent` with the launcher's `ComponentName`, attaches an `ActivityOptions` with `setLaunchBounds(Rect)` (public since API 24) and a reflective `setLaunchWindowingMode(FREEFORM)` (hidden but reachable on Android 9), then calls `context.startActivity(intent, options.toBundle())`. The system honors the bounds and the freeform mode and opens each app in its target rectangle.
3. For app-specific quirks (e.g. Waze's "Android Auto" placeholder lock-up — see `AppConfig.WAZE.preLaunchCommands`), the app *also* tries to run a small set of shell commands via `su`. This is **best-effort**: if `su` isn't reachable from our UID, we surface a warning in the status panel and the launch still proceeds. Root is *not* required for the dual-view feature itself.

No `am stack list` parsing, no `am task resize`, no LSPosed module, no signature requirements.

## First-time device setup

After installing the app for the first time, run **one** `pm grant` from a shell. This survives reboots and app updates; redo it only if you uninstall.

```bash
adb -s <head-unit-serial> shell
# Password: adb36987
pm grant com.xmo.mapvideoplayer android.permission.WRITE_SECURE_SETTINGS
exit
```

That's it. The Changan adb shell already has the privilege to grant signature/privileged permissions to other apps after the adbd password — no `su` needed for this step.

## Build & install

Per the project-wide handoff doc (`AGENT_HANDOFF.md` in the parent folder), **all Gradle builds must run from macOS Terminal.app**, not Cursor's integrated terminal — Tahoe + Cursor + JVM = `EPERM` on `connect()`.

### One-time wrapper bootstrap

Gradle's wrapper jar isn't shipped in this repo. Generate it once from Terminal.app:

```bash
cd /Users/xmo/CursorProjects/Xmo_Map_Video_Player
gradle wrapper --gradle-version 8.9
```

If you don't have a system-installed `gradle`, just open the project in Android Studio once — it sets up the wrapper automatically on first sync.

### Release build + deploy to the car

```bash
# Terminal.app:
cd /Users/xmo/CursorProjects/Xmo_Map_Video_Player
./gradlew assembleRelease

# Either terminal (adb is fine in Cursor):
adb -s <head-unit-serial> push app/build/outputs/apk/release/app-release.apk /data/local/tmp/
adb -s <head-unit-serial> shell
# Password: adb36987
pm install -r -d /data/local/tmp/app-release.apk
exit
```

If `pm install` fails with a vague error, run `df -h /data` in the authenticated shell first — `/data` near-full presents as a generic install failure (see handoff doc).

## Configuring target apps

If the autodetected package or activity names don't match what's actually installed, edit [`AppConfig.kt`](app/src/main/java/com/xmo/mapvideoplayer/AppConfig.kt) — a single object with three `TargetApp` constants. Find the right values via:

```bash
# Authenticated shell:
pm list packages | grep -iE 'youtube|maps|waze'
cmd package resolve-activity --brief <package.name>
```

The split ratio and which app goes on which side are also constants in that file.

## Stage 2 (planned)

The architecture already separates "launch + bounds" (in `WindowController`) from "UI" (in `MainActivity`). When we add stage-2 features, the new wiring is:

| Feature | Mechanism |
|---|---|
| Movable divider | Drag-handle overlay → recompute two `Rect`s → `am task resize` for both task IDs (we remember them from the initial launch) |
| Fullscreen toggle for either side | `am stack movetask TASK_ID HOME_STACK` to hide one, then resize the other to 1920×720 |
| Maps ↔ Waze swap | Trivial — `WindowController.launchSideBySide(leftApp = AppConfig.WAZE, …)`. Already wired into the UI as two start buttons |

The persistent control surface (so you can move the divider while the two apps are running and our launcher activity is in the background) is the unsolved piece — likely a `SYSTEM_ALERT_WINDOW` overlay or a third tiny freeform window. To be decided when we get there.

## Status panel — what the two dots mean

- **WRITE_SECURE_SETTINGS** — required. Red if missing (run the `pm grant` from setup above); green when granted.
- **su** — advisory. Used only for app-specific pre-launch hooks (currently Waze's `pm clear` workaround). Yellow if unavailable; the main dual-view feature still works without it, just with a warning in the launch result.

## Known caveats

- **Freeform decor caption.** Stock AOSP freeform windows have a title-bar with a drag handle and close button. This will eat ~30 dp from the top of each window. We can strip it later via an LSPosed hook (synergistic with the `Voice_Assistance_Hider` project) — same root requirement, same module pattern.
- **Audio focus.** With both Maps and YouTube playing media, normal Android audio-focus rules apply — YouTube will duck while Maps speaks turn-by-turn instructions, then resume. If you want different behavior, configure the Maps voice settings inside Maps itself (mute, lower volume, etc.) — no code change needed.
- **Cold launch latency.** First-time launch of two big apps simultaneously on the constrained SoC takes a few seconds. Subsequent launches (with the apps already warm) are near-instant.
- **Waze workaround needs root (for now).** The `pm clear com.waze` + `pm grant` chain that bypasses Waze's AA-mode lock-up requires either `su` callable from our UID or a future Shizuku integration. Without it, Waze will still launch in the right slot but will hit the AA placeholder screen on its second+ launch — same behavior as launching Waze directly.

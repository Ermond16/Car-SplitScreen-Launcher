# Changan Head Unit Development — Agent Handoff

> Read this first before doing anything. It captures the platform realities and per-project decisions from prior sessions so we don't relitigate them.

## Mission

Develop apps that run on a **Changan CS55 Plus Premium (Egypt) head unit**, prefer **Flutter** as the default stack, and at every decision point be mindful of three real constraints we've already been bitten by:

1. **Limited disk space.** `/data` is tight. A debug APK once filled it and silently broke installs. Use **release** builds for deployment, target what actually needs to ship, prune assets aggressively.
2. **Head unit fragility.** This is a heavily-customized Android 9 from MediaTek's "alps" reference platform with a vendor-locked `adbd`. Things normal Android does (`adb install`, `flutter run -d <head-unit>`, broad permission models) routinely don't apply. Treat the device as a known-quirky black box.
3. **Limited processing power.** Don't assume smooth complex animations, heavy compute, or large native libraries will perform acceptably. Profile early; prefer simple UIs.

**Flutter is the default**, but switch to **native Kotlin/Compose** when the work is dominated by Android system APIs (services, accessibility, window-manager hooks, LSPosed modules, etc.) — Flutter would just add a Dart layer over the same Kotlin code in those cases.

---

## Hardware targets

- **Primary: Changan CS55 Plus Premium (Egypt market).**
  - Display: **1920×720 @ 160 dpi**, landscape, ~12.3".
  - Platform: MediaTek "alps", model `spm8666p1_64_car`, manufacturer `alps`.
  - Android **9 (API 28)**.
- **Secondary: Galaxy S22**, Android 14, model `SM-S901U1`. Standard Android — `adb install` works normally. Useful for sanity-checking app behavior before pushing to the car.

## Dev machine

- Apple Silicon Mac, **macOS 26.4.1 (Tahoe)**.
- IDE: **Cursor** (Meta-distributed build).
- Toolchain (verified):
  - JDK: **Temurin 17.0.19** at `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
  - Android SDK: `/Users/xmo/Library/Android/sdk`.
  - Android Studio installed; AVD Manager works.
  - Flutter **3.41.9** at `/opt/homebrew/bin/flutter`, `flutter config --jdk-dir` set to Temurin 17.
  - ADB on PATH and working against both phone and head unit.
  - **No Xcode** — Android-only.

---

## Critical environment quirks (platform-level, apply to all projects)

### 1. macOS Tahoe + Cursor terminal + JVM = `EPERM` on `connect()`

Any JVM (JBR, Temurin) spawned in Cursor's integrated terminal hits `java.net.SocketException: Operation not permitted` on `Net.connect0` when connecting to localhost. This breaks **every Gradle build run from Cursor's terminal**, including `flutter run`, `flutter build apk`, and `./gradlew` directly.

**Workaround:**
- Run all Gradle / Flutter builds from **macOS Terminal.app**, not Cursor's integrated terminal.
- Cursor's terminal is fine for `adb`, `flutter attach`, file operations, git — anything that isn't a JVM.
- For Flutter hot-reload in Cursor: start `flutter run` from Terminal.app, press `d` (detach — frees the VM service), then `flutter attach` from Cursor's terminal.

**Don't redo this diagnosis.** Things ruled out: login vs non-login shell (envs identical), JBR vs Temurin (both blocked), `org.gradle.daemon=false` (single-use daemons also use TCP), macOS Privacy/Local Network panel (Cursor isn't listed and can't be added). The cause is Tahoe sandbox interaction with Cursor.app's entitlements; not fixable at the project / Gradle / JDK / shell level.

### 2. Changan head unit has a vendor-locked `adbd` — password `adb36987`

Sensitive shell commands (`pm`, `wm`, `settings`, `am`, etc.) prompt interactively for a password.

- **`adb install` does NOT work** on the head unit — it can't satisfy the prompt.
- **`flutter run -d <head-unit>` does NOT work** for the same reason.
- **Deploy recipe** (the only path that works on this device):
  ```bash
  # macOS Terminal.app (because of quirk #1):
  flutter build apk --release      # or ./gradlew assembleRelease for native projects

  # Either terminal — adb is fine in Cursor:
  adb -s <head-unit-serial> push <path-to-apk> /data/local/tmp/
  adb -s <head-unit-serial> shell
  # When prompted, enter: adb36987
  pm install -r -d /data/local/tmp/<apk-name>.apk
  ```

### 3. Low `/data` space presents as a generic install failure

`pm install` prints a vague error with no useful detail when `/data` is near-full. **First diagnostic on any "install fails" symptom**:

```bash
# Authenticated shell:
df -h /data
```

If usage is >95%, free space (delete APKs from `/sdcard/Download/`, etc.) before debugging anything else. We've already burned an hour on this.

### 4. Use release APKs (not debug) for the car

Debug APKs are ~3× larger than release (~45 MB vs ~15 MB for a Hello World) because they ship the full Dart VM in JIT mode plus debug symbols. On a `/data`-constrained head unit, this matters.

- Iterate on the emulator with `flutter run` (debug + hot reload).
- Build for the car with `flutter build apk --release`.
- The default Flutter `targetSdk` (currently 36) installs cleanly on this Android 9 device. **Don't lower it preemptively.**

### 5. Car-shaped emulator is already set up in Android Studio

A working AVD that mimics the Changan head unit's screen exists in the user's Android Studio AVD Manager. Use it for fast iteration before pushing release builds to the car. Specs:

- **Device type:** Tablet (NOT Automotive — that's AAOS, which the Changan is not).
- **Hardware profile:** 1920×720, 160 dpi, no hardware buttons, default landscape.
- **System image:** Android 11 (R) / API 30 / arm64-v8a, plain (no "Google APIs", no "Google Play"). API 30 is acceptable for dev even though the car itself is API 28.

If for any reason the AVD is missing or broken later, recreate it with those exact specs. Verify with:

```bash
adb -s emulator-5554 shell wm size       # expect: Physical size: 1920x720
adb -s emulator-5554 shell wm density    # expect: Physical density: 160
```

### 6. Folder naming

- **Flutter projects:** lowercase snake_case (Dart package-name constraint).
- **Native Kotlin projects:** any case is fine.

---

## Projects

### Project 1: `hello_world_flutter`

- **Path:** `/Users/xmo/CursorProjects/hello_world_flutter`
- **Stack:** Flutter
- **Status:** ✅ Done. Deployed and verified working on both Galaxy S22 and the Changan head unit.

#### Goal
Minimal "Hello, World!" rendered centered on the car screen — a test of the full toolchain (build → push → install → launch) and a reference for car-form-factor conventions.

#### Conventions established (carry to future Flutter projects on the car)
- Lowercase snake_case folder name.
- Manifest: `android:screenOrientation="landscape"` on the launcher activity.
- Dart: `SystemChrome.setPreferredOrientations([landscapeLeft, landscapeRight])` in `main()`.
- Body text: 96 px (legible from the driver's seat on a 12.3" / 160 dpi display).
- Default Flutter targetSdk and minSdk left at framework defaults.

#### What's in the folder
- Standard `flutter create` scaffold with the Hello World UI.
- A README that documents the Changan deploy recipe and the EPERM workaround for Cursor terminal.

#### Useful as
A reference project — its README is the canonical "how do I deploy a Flutter app to the Changan?" cheat sheet. New Flutter projects can copy its `AndroidManifest.xml` orientation lock and its build/deploy commands verbatim.

---

### Project 2: `Voice_Assistance_Hider`

- **Path:** `/Users/xmo/CursorProjects/Voice_Assistance_Hider`
- **Stack:** **Native Kotlin** (NOT Flutter — see "Why native" below)
- **Status:** ⏳ Empty. Awaiting prerequisite checks before scaffolding.

#### Goal
Hide the always-visible floating label of the Changan's built-in voice assistant **without disabling the voice assistant entirely**. The label:

- Is a passive transcript display in the **top-left corner**, ~1/5 of the screen width.
- Has **no dismiss action** — no X, no swipe, no settings option to hide or relocate it.
- Has its window flags including the implicit `FLAG_TOUCHABLE`, so it **intercepts finger touches** that should reach apps underneath (YouTube search, APKPure search, etc.).
- Disabling the voice assistant's "display on top of other apps" permission **breaks the voice assistant** — its core flow depends on the overlay.
- Disabling the voice assistant entirely (the user's current workaround) hides the label but loses voice features. This project's whole point is to keep voice features while killing the input-blocking.

#### Conclusion: this requires ROOT

Non-root approaches were exhaustively analyzed and ruled out:

| Approach | Verdict | Reason |
|---|---|---|
| Draw our own overlay above theirs | **No.** | Their overlay still has `FLAG_TOUCHABLE`. Our overlay either eats touches (no improvement) or has `FLAG_NOT_TOUCHABLE` and lets touches fall through to the next touchable window — which is theirs. |
| Accessibility Service to "dismiss" the label | **No.** | Label is passive; no dismiss action exists. |
| Accessibility touch-exploration + `dispatchGesture` | **No.** | Synthetic gestures go through the same input dispatcher and hit the voice assistant first. |
| `performAction(ACTION_CLICK)` on accessibility nodes | **Bad UX.** | Doesn't feel like a finger touch; doesn't work for typing into search bars; would require per-target-app scripting. |
| Force-stop the voice assistant | **No, without root.** | It runs a foreground service; non-system apps can't kill it. |
| Replace the voice assistant entirely | **Out of scope.** |
| **Hook `WindowManager.addView` and clear `FLAG_TOUCHABLE`** | **✅ Yes, with root + LSPosed.** | The clean fix. ~50 lines of Kotlin. |

#### Why native Kotlin (not Flutter) for this one

LSPosed module APIs are Kotlin/Java only. The UI is minimal (a settings screen at most). Flutter would just add a Dart layer over the same Kotlin work without adding value.

#### Recommended approach
An **LSPosed module** (modern Xposed, Android 9+ compatible) that hooks the voice assistant's `WindowManager.addView` calls and strips `FLAG_TOUCHABLE` from its overlay's `LayoutParams` before `WindowManagerService` sees them. The label remains visible; touches pass through naturally to the app underneath.

#### Prerequisites the user must run before any code is written

These three answers determine whether this project is buildable:

1. **Is the head unit rooted?**
   ```bash
   adb -s <head-unit-serial> shell
   # Password: adb36987
   su
   ```
   - Returns `#` → rooted, proceed.
   - "su: not found" / similar → research rooting MediaTek "alps" Android 9 platforms first. If unrootable, the project is not feasible as scoped — pivot or shelve.

2. **Voice assistant's package name** — with the offending label visible:
   ```bash
   # Authenticated adb shell:
   dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp|Window #'
   ```

3. **Identify the offending overlay view** — with the label visible:
   ```bash
   # Authenticated adb shell:
   uiautomator dump /sdcard/window.xml
   exit
   adb -s <head-unit-serial> pull /sdcard/window.xml
   ```
   The XML reveals the package, resource-id, and bounds of the offending view, which narrows the LSPosed hook to exactly the right window.

#### What the user has already tried
- Disabling "display on top of other apps" for the voice assistant → broke the voice assistant entirely.
- Disabling the voice assistant in car settings → hides the label but loses voice features (this is their current workaround they want to replace).

#### Suggested first-session arc for this project
1. Agent reads this file.
2. User runs the three prerequisite commands above.
3. **Branch on root status:**
   - Rooted: agent scaffolds a Kotlin LSPosed module — `AndroidManifest.xml` with `meta-data android:name="xposedmodule"`, an `IXposedHookLoadPackage` implementation that filters by the voice-assistant's package, hooks `WindowManager.addView`, and strips `FLAG_TOUCHABLE`. User installs LSPosed Manager, enables the module, scopes it to the voice assistant.
   - Not rooted: agent and user investigate rooting paths for the alps platform; if achievable, root first; if not, pivot the project.

---

## What new agents should NOT waste time on

- Re-investigating the Cursor terminal Gradle `EPERM` issue. Use Terminal.app for builds; that's the answer.
- Suggesting Cursor's Flutter extension F5 as a workaround — same EPERM root cause.
- Suggesting `--no-daemon`, login-shell flags, JDK swaps, or env tweaks for the EPERM issue. All tried; none help.
- Recommending non-root approaches to the Voice_Assistance_Hider problem unless we've confirmed the head unit is unrootable AND we're explicitly pivoting that project.
- Lowering Flutter's `targetSdk` preemptively for car deployment — defaults install fine once `/data` has space.
- Picking the "Automotive" device type when creating an AVD for car testing — that's AAOS, which the Changan is not.

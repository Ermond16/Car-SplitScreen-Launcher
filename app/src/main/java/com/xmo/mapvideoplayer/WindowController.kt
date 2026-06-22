package com.xmo.mapvideoplayer

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.util.Log

/**
 * Orchestrates the dual-view launch using Android's *public* multi-window APIs
 * (the same approach FOSS apps like Taskbar use), so we don't depend on root
 * being callable from this app's UID.
 *
 * Mechanism:
 *   1. Ensure freeform multi-window is enabled at the system level by writing
 *      `enable_freeform_support` and `force_resizable_activities` via
 *      Settings.Global. Requires WRITE_SECURE_SETTINGS, which the user grants
 *      once via `adb shell pm grant <our-package> WRITE_SECURE_SETTINGS`.
 *   2. (Optional, best-effort) Run any app-specific pre-launch shell commands
 *      via [ShellRunner] (root). These are used for things like Waze's
 *      `pm clear` AA-mode workaround. If `su` is unavailable, we surface a
 *      warning but do NOT block the launch — the rest still works.
 *   3. For each target app, build an Intent + ActivityOptions with
 *      `setLaunchBounds(bounds)` (public since API 24) and a reflective
 *      `setLaunchWindowingMode(FREEFORM)` (hidden API, present on Android 9).
 *      `context.startActivity(intent, options)` does the rest.
 *
 * No `am start`, no `am stack list` parsing, no shell-out for the core flow.
 */
class WindowController(
    private val context: Context,
    private val shell: ShellRunner,
) {
    sealed class Result {
        data class Success(val warnings: List<String> = emptyList()) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Lays out [leftApp] and [rightApp] side-by-side. [leftRatio] is the
     * fraction of the screen width given to the LEFT window (0.0 .. 1.0).
     */
    fun launchSideBySide(
        leftApp: AppConfig.TargetApp,
        rightApp: AppConfig.TargetApp,
        leftRatio: Float = AppConfig.DEFAULT_LEFT_RATIO,
        screenWidth: Int = AppConfig.SCREEN_WIDTH_PX,
        screenHeight: Int = AppConfig.SCREEN_HEIGHT_PX,
    ): Result {
        // Step 1: turn freeform on at the system level (idempotent).
        if (!tryEnableFreeform()) {
            return Result.Failure(
                "WRITE_SECURE_SETTINGS not granted.\nOne-time setup: " +
                    "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
        }

        // Step 2: run app-specific pre-launch shell commands (e.g. Waze's pm
        // clear). Best-effort: a failure here is a warning, not a failure.
        val warnings = mutableListOf<String>()
        val preLaunchCommands = leftApp.preLaunchCommands + rightApp.preLaunchCommands
        if (preLaunchCommands.isNotEmpty()) {
            val script = preLaunchCommands.joinToString("\n")
            val shellResult = shell.runAsRoot(script)
            if (!shellResult.success) {
                val brief = shellResult.stderr
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?: "exit=${shellResult.exitCode}"
                warnings.add("Waze workaround skipped (su unavailable): $brief")
                Log.w(TAG, "preLaunchCommands failed: $brief")
            }
        }

        // Step 3: compute target bounds.
        val clampedRatio = leftRatio.coerceIn(0.05f, 0.95f)
        val splitX = (screenWidth * clampedRatio).toInt()
        val leftBounds = Rect(0, 0, splitX, screenHeight)
        val rightBounds = Rect(splitX, 0, screenWidth, screenHeight)

        // Step 4: fire the two intents with launch bounds + freeform mode.
        return try {
            launchInFreeform(leftApp, leftBounds)
            // A short delay so the first task is fully placed before the
            // second one starts; otherwise the system can race the second
            // launch and ignore its bounds.
            Thread.sleep(LAUNCH_INTERLEAVE_MS)
            launchInFreeform(rightApp, rightBounds)
            Result.Success(warnings)
        } catch (e: SecurityException) {
            Result.Failure("Activity start denied: ${e.message}")
        } catch (e: Exception) {
            Result.Failure("Launch failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- internal: settings + launch primitives ----

    private fun tryEnableFreeform(): Boolean {
        return try {
            val resolver = context.contentResolver
            Settings.Global.putInt(resolver, "enable_freeform_support", 1)
            Settings.Global.putInt(resolver, "force_resizable_activities", 1)
            Log.d(TAG, "freeform enabled via Settings.Global")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings.Global write denied (WRITE_SECURE_SETTINGS not granted)", e)
            false
        }
    }

    private fun launchInFreeform(app: AppConfig.TargetApp, bounds: Rect) {
        val intent = resolveLaunchIntent(app)
            ?: throw IllegalStateException(
                "No launchable activity for ${app.packageName} — is it installed?"
            )
        // Whatever the resolver returned (explicit or auto), normalize flags so
        // the system places this in a fresh task at our bounds rather than
        // bringing an existing instance forward at its old bounds.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        val options = ActivityOptions.makeBasic().apply {
            setLaunchBounds(bounds)
            tryReflectivelySetWindowingMode(this, WINDOWING_MODE_FREEFORM)
        }

        Log.d(TAG, "starting ${app.displayName} at $bounds via ${intent.component}")
        context.startActivity(intent, options.toBundle())
    }

    /**
     * Build the Intent that targets [app]'s launcher activity.
     *
     * Resolution order:
     *   1. If [AppConfig.TargetApp.activity] is set AND the component actually
     *      resolves on the device, use it explicitly. This is the fast path
     *      for apps with stable, known launcher classes (e.g. ReVanced's
     *      obfuscated activity name).
     *   2. Otherwise fall back to `PackageManager.getLaunchIntentForPackage`.
     *      This is robust to OEM/version variation in the target app's
     *      internal launcher activity class.
     *   3. Returns null only if the package isn't installed at all.
     */
    private fun resolveLaunchIntent(app: AppConfig.TargetApp): Intent? {
        if (!app.activity.isNullOrBlank()) {
            val activityClass = if (app.activity.startsWith(".")) {
                app.packageName + app.activity
            } else {
                app.activity
            }
            val explicit = Intent().apply {
                component = ComponentName(app.packageName, activityClass)
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            if (context.packageManager.resolveActivity(explicit, 0) != null) {
                return explicit
            }
            Log.w(
                TAG,
                "Explicit activity $activityClass not resolvable for ${app.packageName}; " +
                    "falling back to PackageManager.getLaunchIntentForPackage()",
            )
        }
        return context.packageManager.getLaunchIntentForPackage(app.packageName)
    }

    /**
     * `ActivityOptions.setLaunchWindowingMode(int)` is `@hide` on Android 9 but
     * fully functional via reflection — there's no hidden-API restriction at
     * this level on API 28 (our actual deployment target). Without it,
     * `setLaunchBounds` alone often causes the system to launch the app
     * fullscreen and ignore the bounds.
     *
     * Lint flags this for targetSdk 36+ because the method is on the blocklist
     * on newer Android versions. That's a real concern *if* the app ever ran
     * on those — but we deploy to a fixed API 28 device, and even if we
     * didn't, the try/catch below gracefully degrades to "bounds-only launch"
     * on any device where the reflection is blocked. Suppression here is the
     * intentional acknowledgement of that trade-off.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun tryReflectivelySetWindowingMode(options: ActivityOptions, mode: Int) {
        try {
            val method = ActivityOptions::class.java.getDeclaredMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(options, mode)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setLaunchWindowingMode not available; bounds-only launch")
        } catch (e: Exception) {
            Log.w(TAG, "setLaunchWindowingMode reflection failed", e)
        }
    }

    // ---- public diagnostics for the UI ----

    /** True if our app currently holds WRITE_SECURE_SETTINGS (the blocking prerequisite). */
    fun isWriteSecureSettingsGranted(): Boolean {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }

    /** True if `su` is reachable from our UID. Advisory — only needed for the Waze workaround. */
    fun isRootAvailable(): Boolean = shell.isRootAvailable()

    companion object {
        private const val TAG = "WindowController"
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val LAUNCH_INTERLEAVE_MS = 300L
    }
}

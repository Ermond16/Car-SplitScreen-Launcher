package com.xmo.mapvideoplayer

/**
 * Static config for the dual-view layout.
 *
 * Edit the constants here if the autodetected package/activity names don't match
 * what's installed on your head unit. Run `pm list packages | grep -iE 'youtube|maps|waze'`
 * in an authenticated adb shell to confirm, and `cmd package resolve-activity --brief PKG`
 * to get the main activity.
 */
object AppConfig {
    data class TargetApp(
        val displayName: String,
        val packageName: String,
        /**
         * Activity class to launch. Three accepted forms:
         *   - Fully qualified: "com.waze.FreeMapAppActivity"
         *   - Relative (leading dot): ".FreeMapAppActivity" → resolved as packageName + activity
         *   - null: auto-resolve via PackageManager.getLaunchIntentForPackage()
         *
         * Use null whenever you don't strictly need a specific activity — it's
         * robust to OEM/version differences in the target app's launcher class.
         * Use an explicit value only when getLaunchIntentForPackage returns
         * the wrong one (e.g. ReVanced ships a custom obfuscated activity).
         */
        val activity: String? = null,
        /**
         * Shell commands to run as root BEFORE the `am start` for this app.
         * Used for app-specific workarounds (e.g. clearing Waze's state every
         * launch to bypass its "Android Auto" mode). Each entry is one shell
         * line; failures don't abort the launch.
         */
        val preLaunchCommands: List<String> = emptyList(),
    ) {
        val component: String get() = "$packageName/${activity ?: "(auto)"}"
    }

    // ReVanced standalone build (not the drop-in replacement). The activity name
    // is the obfuscated launcher activity ReVanced ships with.
    val YOUTUBE: TargetApp = TargetApp(
        displayName = "YouTube",
        packageName = "app.revanced.android.youtube",
        activity = ".revanced_rounded_2",
    )

    val GOOGLE_MAPS: TargetApp = TargetApp(
        displayName = "Google Maps",
        // ReVanced-style fork of Google Maps that runs without Google Play
        // Services. Side-by-side installable; the package name is renamed
        // from com.google.android.apps.maps but the launcher activity class
        // (com.google.android.maps.MapsActivity) is preserved.
        packageName = "app.revanced.android.apps.maps",
        // activity = null → auto-resolve. Works regardless of which class
        // name the fork uses internally.
    )

    val WAZE: TargetApp = TargetApp(
        displayName = "Waze",
        packageName = "com.waze",
        activity = ".FreeMapAppActivity",
        // Workaround for the "Waze on Android Auto" blank-screen lock-up:
        // Waze flips into AA placeholder mode on any cold start that follows a
        // completed onboarding/login. Clearing data first forces it back to
        // pre-onboarding state, which keeps the normal Waze map UI visible.
        // The user has to tap "Get Started" each launch — guest mode still
        // gets community speed-camera alerts, so this is acceptable. We
        // re-grant location after the clear so the system permission dialog
        // doesn't pop too. To be replaced by an LSPosed hook later if we
        // decide the per-launch tap is too friction-heavy.
        preLaunchCommands = listOf(
            "am force-stop com.waze",
            "pm clear com.waze",
            "pm grant com.waze android.permission.ACCESS_FINE_LOCATION",
            "pm grant com.waze android.permission.ACCESS_COARSE_LOCATION",
        ),
    )

    // ---------- Alternatives that don't depend on Google Play Services ----------
    // The head unit doesn't have GMS, so stock Google Maps is broken there.
    // These OSM-based navigation apps are fully standalone (no Google account,
    // no GMS, no Android Auto detection logic). Swap the LEFT_APP below to
    // pick one. After installing, confirm activity names with:
    //     cmd package resolve-activity --brief <package>

    val OSMAND: TargetApp = TargetApp(
        displayName = "OsmAnd",
        packageName = "net.osmand.plus",
    )

    val ORGANIC_MAPS: TargetApp = TargetApp(
        displayName = "Organic Maps",
        packageName = "app.organicmaps",
    )

    // Changan CS55 Plus Premium — 1920x720 landscape.
    const val SCREEN_WIDTH_PX = 1920
    const val SCREEN_HEIGHT_PX = 720

    // Default 40% / 60% split. The fraction is the width given to the LEFT app.
    // We put Maps on the left (closer to driver in left-hand-drive Egypt, easier
    // to glance at) so the user gets 40% map + 60% YouTube by default.
    // Flip LEFT_APP and RIGHT_APP below if you want YouTube on the left instead.
    const val DEFAULT_LEFT_RATIO: Float = 0.40f

    val LEFT_APP: TargetApp get() = GOOGLE_MAPS
    val RIGHT_APP: TargetApp get() = YOUTUBE
}

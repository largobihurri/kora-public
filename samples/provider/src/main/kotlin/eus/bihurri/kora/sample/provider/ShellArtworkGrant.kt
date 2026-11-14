package eus.bihurri.kora.sample.provider

import android.content.Context
import eus.bihurri.kora.android.KoraAndroid

/**
 * The screen's "Grant artwork to adb shell" switch: whether [SampleExternalAccess] grants the adb shell
 * `media.artwork.read`. On by default (the sample's usual grants); persisted in the sample's own preferences and kept
 * in memory, because the caller policy asks on every call. Only the app's own screen changes it: there is no exported
 * component for it.
 */
object ShellArtworkGrant {
    private const val PREFS = "artwork-sample"
    private const val KEY_GRANTED = "shell_artwork_granted"

    @Volatile
    private var granted = true

    /** Call once from `Application.onCreate`, before the node is installed. */
    fun load(context: Context) {
        granted = prefs(context).getBoolean(KEY_GRANTED, true)
    }

    fun isGranted(): Boolean = granted

    /** Applies at once to the next call; removing a grant also ends the shell's live subscriptions, as a real app must. */
    fun set(context: Context, value: Boolean) {
        granted = value
        prefs(context).edit().putBoolean(KEY_GRANTED, value).apply()
        if (!value) KoraAndroid.revokeUntrustedSubscriptions()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

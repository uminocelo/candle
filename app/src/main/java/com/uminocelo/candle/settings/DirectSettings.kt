package com.uminocelo.candle.settings

import com.uminocelo.candle.R

/** The `android.provider.Settings` tables. */
enum class Table { SYSTEM, SECURE, GLOBAL }

/** Reads and writes Android settings; [AndroidSettingsStore] on the device, a map in tests. */
interface SettingsStore {
    fun get(table: Table, key: String): String?

    /** Returns false when Android refuses the write (the grant is missing). */
    fun put(table: Table, key: String, value: String): Boolean
}

/**
 * What Candle may write. `WRITE_SECURE_SETTINGS` covers the Secure and Global tables;
 * the System table needs the `WRITE_SETTINGS` app op instead. Both are granted by
 * `scripts/deploy.sh`.
 */
data class Grants(val secureSettings: Boolean, val systemSettings: Boolean) {
    fun allows(table: Table): Boolean = if (table == Table.SYSTEM) systemSettings else secureSettings

    val complete: Boolean get() = secureSettings && systemSettings
}

data class Choice(val label: Int, val value: String)

/**
 * A setting Candle changes itself. Every key in [keys] gets the same value; the
 * first one is read back. [default] stands in for a key that was never written.
 */
data class DirectSetting(
    val title: Int,
    val table: Table,
    val keys: List<String>,
    val choices: List<Choice>,
    val default: String,
    /** Ask before switching to this value (e.g. turning adb off). */
    val confirmValue: String? = null,
) {
    /** The choice matching the stored value, or null when it is set to something Candle does not offer. */
    fun current(store: SettingsStore): Choice? {
        val stored = store.get(table, keys.first()) ?: default
        return choices.firstOrNull { sameValue(it.value, stored) }
    }

    /** The choice after the current one; an unknown value moves to the first choice. */
    fun next(store: SettingsStore): Choice {
        val index = choices.indexOf(current(store))
        return choices[(index + 1) % choices.size]
    }

    /** Writes [choice] to every key and returns true when it reads back. */
    fun apply(store: SettingsStore, choice: Choice): Boolean =
        keys.all { store.put(table, it, choice.value) } && current(store) == choice

    fun needsConfirmation(choice: Choice): Boolean = choice.value == confirmValue

    // Animation scales come back as "0", "0.0" or "1.0" depending on who wrote them.
    private fun sameValue(a: String, b: String): Boolean {
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        return if (x != null && y != null) x == y else a == b
    }
}

object DirectSettings {
    private const val NEVER_SCREEN_OFF = Int.MAX_VALUE.toString()

    val ANIMATIONS = DirectSetting(
        title = R.string.setting_animations,
        table = Table.GLOBAL,
        keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale"),
        choices = listOf(Choice(R.string.choice_off, "0"), Choice(R.string.choice_on, "1")),
        default = "1",
    )

    /** Android TV starts the screensaver after the screen-off timeout. */
    val SCREENSAVER = DirectSetting(
        title = R.string.setting_screensaver,
        table = Table.SYSTEM,
        keys = listOf("screen_off_timeout"),
        choices = listOf(
            Choice(R.string.duration_5_min, "300000"),
            Choice(R.string.duration_15_min, "900000"),
            Choice(R.string.duration_30_min, "1800000"),
            Choice(R.string.duration_1_h, "3600000"),
            Choice(R.string.duration_never, NEVER_SCREEN_OFF),
        ),
        default = NEVER_SCREEN_OFF,
    )

    /** Android TV's "put device to sleep" delay; -1 is never. */
    val SLEEP = DirectSetting(
        title = R.string.setting_sleep,
        table = Table.SECURE,
        keys = listOf("sleep_timeout"),
        choices = listOf(
            Choice(R.string.duration_30_min, "1800000"),
            Choice(R.string.duration_1_h, "3600000"),
            Choice(R.string.duration_4_h, "14400000"),
            Choice(R.string.duration_never, "-1"),
        ),
        default = "-1",
    )

    /** adb over Wi-Fi: needs no authorization on the HY300, so keep it off when not developing. */
    val NETWORK_DEBUGGING = DirectSetting(
        title = R.string.setting_network_debugging,
        table = Table.GLOBAL,
        keys = listOf("adb_enabled"),
        choices = listOf(Choice(R.string.choice_off, "0"), Choice(R.string.choice_on, "1")),
        default = "0",
        confirmValue = "0",
    )

    val ALL = listOf(ANIMATIONS, SCREENSAVER, SLEEP, NETWORK_DEBUGGING)
}

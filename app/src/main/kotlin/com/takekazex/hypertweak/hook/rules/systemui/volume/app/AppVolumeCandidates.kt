package com.takekazex.hypertweak.hook.rules.systemui.volume.app

/**
 * Which players get an app column. Mirrors the host's own rules so a column never targets a
 * player the system would refuse:
 * - `PlaybackActivityMonitorStubImpl.setPlayerVolume` drops client UIDs below 10000 and only
 *   applies the gain to a player whose usage is MEDIA or whose volume stream is MUSIC;
 * - its gain table is keyed by `PackageManager.getNameForUid(uid)`, so players sharing that key
 *   (an app and its second-space clone, or several players of one app) share one column.
 *
 * A column appears for a key with a started player. While the dialog is showing, a column stays
 * as long as its key still has any player, so pausing from the notification does not pull the
 * slider out from under the finger.
 */
internal object AppVolumeCandidates {
    const val STATE_STARTED = 2
    private const val FIRST_APPLICATION_UID = 10000
    private const val PER_USER_RANGE = 100000
    private const val USAGE_MEDIA = 1
    private const val STREAM_MUSIC = 3
    private val EXCLUDED = setOf("com.miui.miwallpaper")

    data class Player(val uid: Int, val state: Int, val usage: Int, val stream: Int, val key: String?, val token: Any? = null)

    data class Row(val key: String, val players: List<Player>) {
        val uids: List<Int> get() = players.map { it.uid }.distinct()
    }

    fun adjustable(player: Player): Boolean =
        player.uid >= FIRST_APPLICATION_UID && player.uid % PER_USER_RANGE >= FIRST_APPLICATION_UID &&
            (player.usage == USAGE_MEDIA || player.stream == STREAM_MUSIC) &&
            !player.key.isNullOrEmpty() && player.key !in EXCLUDED

    /**
     * Rows in a stable order: previously shown keys keep their place ([retained], used only while
     * the dialog is showing), new keys follow in report order. Each row keeps one representative
     * player per UID, preferring a started one, because the host applies a write to every player
     * of that UID.
     */
    fun select(players: List<Player>, retained: List<String> = emptyList()): List<Row> {
        val grouped = LinkedHashMap<String, MutableList<Player>>()
        players.filter(::adjustable).forEach { grouped.getOrPut(it.key!!) { mutableListOf() } += it }
        val visible = grouped.filter { (key, list) -> key in retained || list.any { it.state == STATE_STARTED } }
        val order = retained.filter(visible::containsKey) + visible.keys.filterNot(retained::contains)
        return order.map { key ->
            val representatives = visible.getValue(key).groupBy { it.uid }.values.map { perUid ->
                perUid.firstOrNull { it.state == STATE_STARTED } ?: perUid.first()
            }
            Row(key, representatives)
        }
    }

    /** Gain ratio shown as an integer percentage, the unit MiSound's own slider uses. */
    fun percent(gain: Float): Int = (gain.coerceIn(0f, 1f) * 100f + 0.5f).toInt()
}

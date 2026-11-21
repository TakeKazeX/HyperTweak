package com.takekazex.hypertweak.util.platform

import android.content.Context
import android.os.UserHandle
import com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object AnalyticsPackageControl {
    data class State(val disabled: Boolean = false, val pending: Boolean = false, val failed: Boolean = false)
    private data class User(val id: Int, val serial: Int)
    private data class Journal(val disable: Boolean, val previous: Map<User, Int>)

    private val lock = ReentrantLock()
    private val packageName = AnalyticsSettingsKeys.PACKAGE
    private val userLine by lazy { Regex("""^\s*UserInfo\{(\d+):.*\} serialNo=(\d+)\b""") }
    private val stateLine by lazy { Regex("""^\s+User (\d+):.*\binstalled=(true|false)\b.*\benabled=([0-4])\b""") }

    // Only the owner's copy of the module can manage the device-wide package policy.
    fun canManage(): Boolean = android.os.Process.myUserHandle() == UserHandle.getUserHandleForUid(0)

    suspend fun update(context: Context, disable: Boolean? = null): State =
        withContext(Dispatchers.IO) { reconcile(context, disable) }

    fun reconcile(context: Context, request: Boolean? = null): State = lock.withLock {
        val store = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("analytics_package_state", Context.MODE_PRIVATE)
        var journal = Journal(store.getBoolean("disable", false), store.getStringSet("previous", emptySet()).orEmpty()
            .associate { entry ->
                val (id, serial, state) = entry.split(':').map(String::toInt)
                User(id, serial) to state
            })
        val desired = request ?: journal.disable
        if (!desired && journal.previous.isEmpty()) {
            if (journal.disable) check(store.edit().putBoolean("disable", false).commit())
            WakeReceiverGate.set(context, WakeReceiverGate.ANALYTICS, false)
            return State()
        }

        fun save(value: Journal) {
            check(store.edit().putBoolean("disable", value.disable).putStringSet("previous", value.previous
                .mapTo(HashSet()) { (user, state) -> "${user.id}:${user.serial}:$state" }).commit())
            journal = value
        }

        try {
            val before = installedStates()
            if (desired && before.isEmpty() && request == true) error("Analytics is not installed")
            if (desired) {
                val enabled = before.filterValues { it == 0 || it == 1 || it == 4 }
                // Journal every state before touching any user. Keep the original state on retries.
                WakeReceiverGate.set(context, WakeReceiverGate.ANALYTICS, true)
                save(Journal(true, enabled + journal.previous.filterKeys { it in before }))
                applyStates(enabled.keys.associateWith { 3 })
                check(installedStates().values.all { it == 2 || it == 3 }) { "Analytics is still enabled for a user" }
                State(disabled = true)
            } else {
                save(journal.copy(disable = false))
                val restore = journal.previous.filterKeys { before[it] == 3 }
                applyStates(restore)
                val after = installedStates()
                check(restore.all { (user, state) -> after[user]?.let { it == state } != false }) {
                    "Analytics package state was not restored"
                }
                save(Journal(false, emptyMap()))
                WakeReceiverGate.set(context, WakeReceiverGate.ANALYTICS, false)
                State()
            }
        } catch (e: Exception) {
            DebugLog.w("AnalyticsPackage", "Cross-user package reconciliation failed", e)
            State(disabled = journal.disable, pending = journal.disable || journal.previous.isNotEmpty(), failed = true)
        }
    }

    private fun installedStates(): Map<User, Int> {
        val output = root("dumpsys user && echo __ANALYTICS_PACKAGE__ && dumpsys package $packageName")
        val (userDump, fullPackageDump) = output.split("\n__ANALYTICS_PACKAGE__\n", limit = 2)
        val users = userDump.lineSequence().mapNotNull { userLine.find(it) }.associate {
            val id = it.groupValues[1].toInt()
            id to User(id, it.groupValues[2].toInt())
        }
        check(0 in users) { "Cannot enumerate Android users" }
        val packageDump = fullPackageDump.substringBefore("\nHidden system packages:")
        val packageHeader = "  Package [$packageName]"
        if (packageDump.lineSequence().none { it.startsWith(packageHeader) }) {
            check("Unable to find package: $packageName" in fullPackageDump) { "Cannot read the Analytics package" }
            return emptyMap()
        }
        val states = packageDump.lineSequence().mapNotNull { stateLine.find(it) }.associate {
            it.groupValues[1].toInt() to ((it.groupValues[2] == "true") to it.groupValues[3].toInt())
        }
        check(states.keys.containsAll(users.keys)) { "Incomplete per-user Analytics package state" }
        return users.mapNotNull { (id, user) ->
            val (installed, state) = states.getValue(id)
            if (installed) user to state else null
        }.toMap()
    }

    private fun applyStates(states: Map<User, Int>) {
        if (states.isEmpty()) return
        root(states.entries.joinToString(" && ") { (user, state) ->
            // Recheck the serial at write time so a recycled user ID cannot inherit an old policy.
            val verb = when (state) {
                0 -> "default-state"
                1 -> "enable"
                3 -> "disable-user"
                4 -> "disable-until-used"
                else -> error("Unexpected Analytics package state $state")
            }
            "if dumpsys user | grep -qE '^[[:space:]]*UserInfo\\{${user.id}:.*} serialNo=${user.serial}([[:space:]]|$)'; " +
                "then pm $verb --user ${user.id} $packageName; fi"
        })
    }

    private fun root(command: String): String = NativeRootCommand.run(command, maxBytes = 1024 * 1024)
        .toString(Charsets.UTF_8)
}

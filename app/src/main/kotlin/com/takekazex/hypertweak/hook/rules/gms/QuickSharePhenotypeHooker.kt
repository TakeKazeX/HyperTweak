package com.takekazex.hypertweak.hook.rules.gms

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Unlocks Nearby Share (Quick Share) on CN (domestic) Google Play services.
 *
 * CN GMS disables Quick Share through two independent gates, both driven by the CN marker
 * features `com.google.android.feature.services_updater` + `cn.google.services`:
 *
 * 1. **Init gate** — the stable
 *    `com.google.android.gms.nearby.sharing.ModuleInitializer(Context) -> boolean` method
 *    returns the `sharing_supports_latchsky` flag (`com.google.android.gms.nearby`)
 *    when the CN features are present, and the flag ships `false` on CN builds, so the runtime
 *    initialization logs `UNSUPPORTED_DEVICE_TYPE_LATCHSKY` and never starts sharing.
 * 2. **Device-type gate** — a GMS method keyed by the CN feature markers classifies a device as
 *    "latchsky", which makes `dqvq.f()` mark it as a blacklisted device type; the discovery
 *    service and UI (`imck.d/e`) then refuse to scan, and `egph.a()` (used by
 *    `GAccountUtils#getSupportedAccounts`) reports the latchsky account failure.
 *
 * The persistent fix is an override row in GMS's credential-encrypted `phenotype.db`
 * (`/data/user/0/com.google.android.gms/databases/phenotype.db`): `sharing_supports_latchsky`
 * in `flag_overrides` (or legacy `FlagOverrides`) with `active IS 1`, `type = 2`, `value = 1`,
 * keyed by `config_package_name` + the wildcard `*` account, which wins over server-delivered
 * flag values at read time.
 *
 * Flag delivery however does **not** read the SQLite row directly: the flag store serves
 * per-package protobuf snapshots (`phenotype/shared/<...>.pb`) that are rebuilt from
 * `config_packages.flags_content` + `flag_overrides` by the persistent-process flag store, and
 * a plain DB write alone is not guaranteed to trigger a rebuild. The hooker therefore combines
 * four layers (all idempotent, all gated on [Preferences.KEY_QUICK_SHARE_ENABLED]):
 *
 * 1. Raw DB override row (persistent, survives reboots and server syncs).
 * 2. The official `com.google.android.gms.phenotype.FLAG_OVERRIDE` broadcast (sent from the GMS
 *    process, which holds the `PHENOTYPE_OVERRIDE_FLAGS` signature permission): `FlagOverride
 *    ChimeraReceiver` runs the `SetFlagOverridesOperationCall` (`fldk`/`fldm`) that commits the
 *    override with GMS's own account semantics and rebuilds the shared `.pb` snapshots, then
 *    broadcasts `com.google.android.gms.phenotype.COMMITTED`, which re-runs `ModuleInitializer`.
 *    Sent only when the DB state actually changed.
 * 3. Resolve the initializer gate by its stable owner and method prototype, then return true so
 *    sharing initializes even if a future GMS version changes flag delivery.
 * 4. Resolve the optional CN device-type gate from its feature strings and method prototype, then
 *    return false to open discovery and account metadata on builds with a unique match.
 *
 * Schema facts verified in `com.google.android.gms-OS4-device.apk` (26.31.31): current schema
 * `flag_overrides(config_package_name, account_id, active, name, value, type, source)` +
 * `accounts` (wildcard `*` matches every account at read time), legacy pre-1001
 * `FlagOverrides(packageName, user, name, flagType, intVal, boolVal, floatVal, stringVal,
 * extensionVal, committed)`; `type 2` = boolean; `source 0` = local user override. Both SQL
 * shapes were validated against a real SQLite built from the extracted schema, including GMS's
 * read-side EXISTS query.
 */
object QuickSharePhenotypeHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "QuickShare"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val PHENOTYPE_DB = "phenotype.db"
    private const val FLAG_PACKAGE = "com.google.android.gms.nearby"
    private const val FLAG_NAME = "sharing_supports_latchsky"
    private const val FLAG_TYPE_BOOL = 2L
    private const val WILDCARD_ACCOUNT = "*"

    /** Stable (non-obfuscated) latchsky module initializer; the gate the flag feeds. */
    private const val MODULE_INITIALIZER_CLASS = "com.google.android.gms.nearby.sharing.ModuleInitializer"

    private const val CN_DEVICE_FEATURE = "services_updater"
    private const val CN_SERVICES_FEATURE = "cn.google.services"

    /** GMS's official override-commit entry point; protected by its own signature permission. */
    private const val FLAG_OVERRIDE_ACTION = "com.google.android.gms.phenotype.FLAG_OVERRIDE"

    private val hooksInstalled = AtomicBoolean(false)

    /**
     * Process-local snapshot of the preference, so the hook callbacks do not depend on
     * [Preferences] being initialized on the calling thread at the exact moment `ModuleInitializer`
     * runs (a secondary-user GMS process can initialize nearby sharing before the module's
     * package-ready context arrives). Refreshed from [Preferences] whenever it is initialized.
     */
    @Volatile
    private var enabledCache = false

    override fun onPrepareHotReload() {
        hooksInstalled.set(false)
    }

    override fun onHook() {
        if (hookParam.packageName != GMS_PACKAGE) return
        enabledCache = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
        installGateHooks()
    }

    /** Called from HookEntry once the GMS app context exists (every GMS process). */
    fun onPackageReady(context: Context) {
        // Only the main GMS process writes the DB and sends the broadcast; other GMS processes
        // would only contend on the same SQLite file. Falls back to running if not attached yet.
        val isMain = runCatching { hookParam.isMainProcess }.getOrDefault(true)
        if (!isMain) return

        // Preferences is initialized by the caller before this point.
        enabledCache = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
        val enabled = enabledCache
        val appContext = context.applicationContext ?: context
        Thread {
            try {
                runOnce(appContext, enabled, forceBroadcast = false)
            } catch (t: Throwable) {
                // The CE database can be temporarily unavailable (early boot before first
                // unlock, or a transient write lock). Retry once; each later GMS start retries.
                DebugLog.w(TAG, "phenotype override failed, retrying in 30s", t)
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { runOnce(appContext, enabled, forceBroadcast = true) }
                    .onFailure { DebugLog.e(TAG, "phenotype override retry failed", it) }
            }
        }.apply {
            name = "HyperTweak-Phenotype"
            isDaemon = true
        }.start()
    }

    private fun quickShareEnabled(): Boolean {
        if (Preferences.isInitialized) {
            enabledCache = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
        }
        return enabledCache
    }

    private fun installGateHooks() {
        if (!hooksInstalled.compareAndSet(false, true)) return

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            hooksInstalled.set(false)
            DebugLog.hookSkipped(TAG, "Quick Share gate", "GMS source APK unavailable")
            return
        }
        val targets = resolveGateTargets(apkPath) ?: run {
            hooksInstalled.set(false)
            DebugLog.hookSkipped(TAG, "Quick Share gate", "unique DexKit initializer not found")
            return
        }

        // The class name is a stable GMS API anchor. DexKit resolves its Context -> boolean gate
        // by prototype; method names are not carried across GMS releases.
        runCatching {
            val eMethod = targets.initializer.apply { isAccessible = true }
            deoptimize(eMethod)
            targets.initializerCallers.forEach(::deoptimize)
            eMethod.hook {
                after { param ->
                    if (quickShareEnabled()) param.result = true
                }
            }
            DebugLog.d(TAG, "ModuleInitializer gate hooked via DexKit: ${eMethod.toGenericString()}")
        }.onFailure { DebugLog.e(TAG, "ModuleInitializer gate hook failed", it) }

        // Best-effort layer: the CN device-type classifier (opens discovery and the account
        // metadata path). A missing or ambiguous marker skips this optional path.
        runCatching {
            val iMethod = targets.cnDeviceCheck ?: return@runCatching
            iMethod.isAccessible = true
            deoptimize(iMethod)
            iMethod.hook {
                after { param ->
                    if (quickShareEnabled()) param.result = false
                }
            }
            DebugLog.d(TAG, "CN device check hooked via DexKit: ${iMethod.toGenericString()}")
        }.onFailure { DebugLog.e(TAG, "CN device check hook failed", it) }
    }

    private data class GateTargets(
        val initializer: Method,
        val initializerCallers: List<Method>,
        val cnDeviceCheck: Method?
    )

    private fun resolveGateTargets(apkPath: String): GateTargets? =
        DexKitManager.withBridge(apkPath) { bridge ->
            val initializerData = bridge.findMethod {
                matcher {
                    declaredClass(MODULE_INITIALIZER_CLASS)
                    paramTypes(Context::class.java)
                    returnType(Boolean::class.javaPrimitiveType!!)
                    addUsingString("android.hardware.bluetooth", StringMatchType.Equals)
                    addUsingString("android.hardware.bluetooth_le", StringMatchType.Equals)
                }
            }.toList().singleOrNull() ?: run {
                DebugLog.w(TAG, "ModuleInitializer Bluetooth capability gate is ambiguous or missing")
                return@withBridge null
            }
            val initializer = materialize(initializerData) ?: return@withBridge null
            val callers = initializerData.callers.toList()
                .filter { data ->
                    data.className == MODULE_INITIALIZER_CLASS &&
                        data.paramTypeNames == listOf(Intent::class.java.name) &&
                        data.returnTypeName == Void.TYPE.name
                }
                .mapNotNull(::materialize)
            val cnCandidates = bridge.findMethod {
                matcher {
                    paramTypes(Context::class.java)
                    returnType(Boolean::class.javaPrimitiveType!!)
                    addUsingString(CN_DEVICE_FEATURE, StringMatchType.Equals)
                    addUsingString(CN_SERVICES_FEATURE, StringMatchType.Equals)
                }
            }.toList().mapNotNull(::materialize)
                .filter(::isBooleanContextGate)
            GateTargets(
                initializer = initializer,
                initializerCallers = callers,
                cnDeviceCheck = cnCandidates.singleOrNull()
            )
        }

    private fun isBooleanContextGate(method: Method): Boolean =
        method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun materialize(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()

    // ─── Package-ready flow: DB row + official override commit ────────────────────

    private fun runOnce(context: Context, enabled: Boolean, forceBroadcast: Boolean) {
        val changed = applyOverride(context, enabled)
        if (forceBroadcast || changed) {
            triggerFlagOverride(context, enabled)
        } else {
            DebugLog.d(TAG, "override state unchanged; no commit broadcast needed")
        }
    }

    /**
     * Sends GMS's official `FLAG_OVERRIDE` broadcast so `FlagOverrideChimeraReceiver` commits
     * the override with GMS's own account semantics, rebuilds the shared `.pb` snapshots and
     * broadcasts `phenotype.COMMITTED` (which re-runs `ModuleInitializer`). Only GMS's own
     * process can send it — the receiver is protected by the signature permission
     * `PHENOTYPE_OVERRIDE_FLAGS`.
     */
    private fun triggerFlagOverride(context: Context, commit: Boolean) {
        val intent = Intent(FLAG_OVERRIDE_ACTION).apply {
            setPackage(GMS_PACKAGE)
            putExtra("package", FLAG_PACKAGE)
            putExtra("user", WILDCARD_ACCOUNT)
            if (commit) {
                putExtra("commit", true)
                putExtra("flags", arrayOf(FLAG_NAME))
                putExtra("values", arrayOf("true"))
                putExtra("types", arrayOf("boolean"))
            } else {
                putExtra("action", "delete")
                putExtra("flag", FLAG_NAME)
            }
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { DebugLog.e(TAG, "FLAG_OVERRIDE broadcast failed", it) }
        DebugLog.d(TAG, "FLAG_OVERRIDE broadcast sent (commit=$commit)")
    }

    // ─── phenotype.db override row ─────────────────────────────────────────────────

    /** Returns true when the DB state actually changed (row inserted/removed). */
    private fun applyOverride(context: Context, enabled: Boolean): Boolean {
        val dbFile = context.getDatabasePath(PHENOTYPE_DB)
        if (!dbFile.exists()) {
            // GMS creates the database lazily; the next GMS start retries.
            DebugLog.d(TAG, "${dbFile.name} not created yet; skipping")
            return false
        }
        val openParams = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE)
            .build()
        return SQLiteDatabase.openDatabase(dbFile, openParams).use { db ->
            // Do not fail on a short-lived write lock held by GMS's own flag store. Set the busy
            // timeout through rawQuery: on current Android, PRAGMA statements must go through
            // query/rawQuery ("Queries can be performed using SQLiteDatabase query or rawQuery
            // methods only"), so execSQL("PRAGMA busy_timeout = ...") would throw.
            db.rawQuery("PRAGMA busy_timeout = 5000", null).use { }
            db.beginTransaction()
            try {
                val changed = when {
                    hasTable(db, "flag_overrides") -> {
                        if (enabled) setCurrentSchemaOverride(db) else clearCurrentSchemaOverride(db)
                    }
                    hasTable(db, "FlagOverrides") -> {
                        if (enabled) setLegacySchemaOverride(db) else clearLegacySchemaOverride(db)
                    }
                    else -> {
                        DebugLog.w(TAG, "no flag override table in phenotype.db")
                        false
                    }
                }
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }
    }

    // ─── Current schema (flag_overrides + accounts) ────────────────────────────────

    private fun setCurrentSchemaOverride(db: SQLiteDatabase): Boolean {
        // Wildcard account matches every account at read time, so one row covers all users.
        db.execSQL("INSERT OR IGNORE INTO accounts (name) VALUES ('$WILDCARD_ACCOUNT')")
        val accountId = queryLong(
            db,
            "SELECT account_id FROM accounts WHERE name = ?",
            WILDCARD_ACCOUNT
        ) ?: run {
            DebugLog.e(TAG, "wildcard account row missing after insert")
            return false
        }
        val applied = db.rawQuery(
            "SELECT 1 FROM flag_overrides " +
                "WHERE config_package_name = ? AND name = ? AND account_id = ? " +
                "AND active IS 1 AND type = $FLAG_TYPE_BOOL AND value = 1 LIMIT 1",
            arrayOf(FLAG_PACKAGE, FLAG_NAME, accountId.toString())
        ).use(Cursor::moveToFirst)
        if (applied) return false

        if (hasColumn(db, "flag_overrides", "source")) {
            db.execSQL(
                "INSERT OR REPLACE INTO flag_overrides " +
                    "(config_package_name, account_id, active, name, value, type, source) " +
                    "VALUES (?, ?, 1, ?, 1, $FLAG_TYPE_BOOL, 0)",
                arrayOf(FLAG_PACKAGE, accountId.toString(), FLAG_NAME)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO flag_overrides " +
                    "(config_package_name, account_id, active, name, value, type) " +
                    "VALUES (?, ?, 1, ?, 1, $FLAG_TYPE_BOOL)",
                arrayOf(FLAG_PACKAGE, accountId.toString(), FLAG_NAME)
            )
        }
        DebugLog.d(TAG, "override written: $FLAG_PACKAGE:$FLAG_NAME = true")
        return true
    }

    private fun clearCurrentSchemaOverride(db: SQLiteDatabase): Boolean {
        val removed = db.delete(
            "flag_overrides",
            "config_package_name = ? AND name = ?",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        if (removed > 0) DebugLog.d(TAG, "override removed ($removed row(s))")
        return removed > 0
    }

    // ─── Legacy schema (pre-1001 FlagOverrides table) ─────────────────────────────

    private fun setLegacySchemaOverride(db: SQLiteDatabase): Boolean {
        db.execSQL(
            "INSERT OR REPLACE INTO FlagOverrides " +
                "(packageName, user, name, flagType, boolVal, committed) " +
                "VALUES (?, '*', ?, $FLAG_TYPE_BOOL, 1, 0)",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        DebugLog.d(TAG, "legacy override written: $FLAG_PACKAGE:$FLAG_NAME = true")
        return true
    }

    private fun clearLegacySchemaOverride(db: SQLiteDatabase): Boolean {
        val removed = db.delete(
            "FlagOverrides",
            "packageName = ? AND name = ?",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        if (removed > 0) DebugLog.d(TAG, "legacy override removed ($removed row(s))")
        return removed > 0
    }

    // ─── Small SQLite helpers ──────────────────────────────────────────────────────

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table)
        ).use(Cursor::moveToFirst)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }
    }

    private fun queryLong(db: SQLiteDatabase, sql: String, vararg args: String): Long? {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }
}

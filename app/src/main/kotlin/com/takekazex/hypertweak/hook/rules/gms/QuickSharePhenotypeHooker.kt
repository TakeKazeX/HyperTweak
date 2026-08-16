package com.takekazex.hypertweak.hook.rules.gms

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Unlocks Nearby Share (Quick Share) on CN (domestic) Google Play services.
 *
 * CN GMS ships the phenotype flag `sharing_supports_latchsky` (flag package
 * `com.google.android.gms.nearby`) as `false`, and GMS's latchsky (Quick Share) module
 * initializer refuses to enable Quick Share while it is false — even when the device has
 * Bluetooth/BLE, runs user 0 as the primary user, and has no `no_bluetooth_sharing`
 * restriction. The persistent fix is a one-time override row in GMS's credential-encrypted
 * `phenotype.db` (`/data/user/0/com.google.android.gms/databases/phenotype.db`), which wins
 * over server-delivered flag values at read time.
 *
 * The hooker runs inside the GMS process (GMS must be in the module's Xposed scope) and
 * enforces the override row at every package-ready: the row is upserted to `true` while
 * [Preferences.KEY_QUICK_SHARE_ENABLED] is on and deleted while it is off, so a GMS restart
 * (or a later boot) always converges. GMS's own `PhenotypeDbHelper` stores flags in
 * `phenotype.db`; two schema generations are handled:
 *
 * - Current schema (schema >= 1001): table `flag_overrides(config_package_name, account_id,
 *   active, name, value, type, source)` plus `accounts`. Overrides are matched at read time by
 *   `(config_packages.name = ? OR flag_overrides.config_package_name IS ?)` and
 *   `(accounts.name = ? OR accounts.name = '*')`, so a row keyed by `config_package_name` with
 *   the wildcard `*` account applies to every user. `type = 2` is boolean, `value = 1` is true,
 *   `source = 0` marks a local user override (which the commit path treats as authoritative),
 *   and `active IS 1` rows are the applied ones.
 * - Legacy schema (pre-1001): table `FlagOverrides(packageName, user, name, flagType, intVal,
 *   boolVal, floatVal, stringVal, extensionVal, committed)` with `flagType = 2` for boolean.
 *
 * Verified against `com.google.android.gms-OS4-device.apk` (26.31.31): the flag is read through
 * `jnjr.dL()` → `guyf.a.i(261, "sharing_supports_latchsky", false)` and belongs to the flag
 * container `jngf.a = guwl("com.google.android.gms.nearby", ...)`. No GMS class is hooked, so
 * a GMS update cannot break the hook; only the SQLite schema is touched.
 */
object QuickSharePhenotypeHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "QuickShare"
    private const val PHENOTYPE_DB = "phenotype.db"
    private const val FLAG_PACKAGE = "com.google.android.gms.nearby"
    private const val FLAG_NAME = "sharing_supports_latchsky"
    private const val FLAG_TYPE_BOOL = 2L
    private const val WILDCARD_ACCOUNT = "*"

    /** Called from HookEntry once the GMS app context exists (every GMS process). */
    fun onPackageReady(context: Context) {
        // Only the main GMS process performs the write; other GMS processes would only
        // contend on the same SQLite file. Falls back to running if not attached yet.
        val isMain = runCatching { hookParam.isMainProcess }.getOrDefault(true)
        if (!isMain) return

        val enabled = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
        val appContext = context.applicationContext ?: context
        Thread {
            try {
                applyOverride(appContext, enabled)
            } catch (t: Throwable) {
                // The CE database can be temporarily unavailable (early boot before first
                // unlock, or a transient write lock). Retry once; each later GMS start retries.
                DebugLog.w(TAG, "phenotype override failed, retrying in 30s", t)
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { applyOverride(appContext, enabled) }
                    .onFailure { DebugLog.e(TAG, "phenotype override retry failed", it) }
            }
        }.apply {
            name = "HyperTweak-Phenotype"
            isDaemon = true
        }.start()
    }

    private fun applyOverride(context: Context, enabled: Boolean) {
        val dbFile = context.getDatabasePath(PHENOTYPE_DB)
        if (!dbFile.exists()) {
            // GMS creates the database lazily; the next GMS start retries.
            DebugLog.d(TAG, "${dbFile.name} not created yet; skipping")
            return
        }
        val openParams = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE)
            .build()
        SQLiteDatabase.openDatabase(dbFile, openParams).use { db ->
            // Do not fail on a short-lived write lock held by GMS's own flag store.
            db.execSQL("PRAGMA busy_timeout = 5000")
            db.beginTransaction()
            try {
                when {
                    hasTable(db, "flag_overrides") -> {
                        if (enabled) setCurrentSchemaOverride(db) else clearCurrentSchemaOverride(db)
                    }
                    hasTable(db, "FlagOverrides") -> {
                        if (enabled) setLegacySchemaOverride(db) else clearLegacySchemaOverride(db)
                    }
                    else -> DebugLog.w(TAG, "no flag override table in phenotype.db")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    // ─── Current schema (flag_overrides + accounts) ────────────────────────────────

    private fun setCurrentSchemaOverride(db: SQLiteDatabase) {
        // Wildcard account matches every account at read time, so one row covers all users.
        db.execSQL("INSERT OR IGNORE INTO accounts (name) VALUES ('$WILDCARD_ACCOUNT')")
        val accountId = queryLong(
            db,
            "SELECT account_id FROM accounts WHERE name = ?",
            WILDCARD_ACCOUNT
        ) ?: run {
            DebugLog.e(TAG, "wildcard account row missing after insert")
            return
        }
        val applied = db.rawQuery(
            "SELECT 1 FROM flag_overrides " +
                "WHERE config_package_name = ? AND name = ? AND account_id = ? " +
                "AND active IS 1 AND type = $FLAG_TYPE_BOOL AND value = 1 LIMIT 1",
            arrayOf(FLAG_PACKAGE, FLAG_NAME, accountId.toString())
        ).use(Cursor::moveToFirst)
        if (applied) return

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
    }

    private fun clearCurrentSchemaOverride(db: SQLiteDatabase) {
        val removed = db.delete(
            "flag_overrides",
            "config_package_name = ? AND name = ?",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        if (removed > 0) DebugLog.d(TAG, "override removed ($removed row(s))")
    }

    // ─── Legacy schema (pre-1001 FlagOverrides table) ─────────────────────────────

    private fun setLegacySchemaOverride(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT OR REPLACE INTO FlagOverrides " +
                "(packageName, user, name, flagType, boolVal, committed) " +
                "VALUES (?, '*', ?, $FLAG_TYPE_BOOL, 1, 0)",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        DebugLog.d(TAG, "legacy override written: $FLAG_PACKAGE:$FLAG_NAME = true")
    }

    private fun clearLegacySchemaOverride(db: SQLiteDatabase) {
        val removed = db.delete(
            "FlagOverrides",
            "packageName = ? AND name = ?",
            arrayOf(FLAG_PACKAGE, FLAG_NAME)
        )
        if (removed > 0) DebugLog.d(TAG, "legacy override removed ($removed row(s))")
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

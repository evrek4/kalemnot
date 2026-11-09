package com.kalemnot.app.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kalemnot", Context.MODE_PRIVATE)

    var fingerDraws: Boolean
        get() = sp.getBoolean("fingerDraws", true)
        set(v) { sp.edit().putBoolean("fingerDraws", v).apply() }

    var stylusAutoSet: Boolean
        get() = sp.getBoolean("stylusAutoSet", false)
        set(v) { sp.edit().putBoolean("stylusAutoSet", v).apply() }

    var penColor: Int
        get() = sp.getInt("penColor", 0xFF1B1B1F.toInt())
        set(v) { sp.edit().putInt("penColor", v).apply() }

    var hlColor: Int
        get() = sp.getInt("hlColor", 0xFFFFEB3B.toInt())
        set(v) { sp.edit().putInt("hlColor", v).apply() }

    var sizeIdx: Int
        get() = sp.getInt("sizeIdx", 1)
        set(v) { sp.edit().putInt("sizeIdx", v).apply() }

    var backupUri: String?
        get() = sp.getString("backupUri", null)
        set(v) { sp.edit().putString("backupUri", v).apply() }

    var autoBackup: Boolean
        get() = sp.getBoolean("autoBackup", false)
        set(v) { sp.edit().putBoolean("autoBackup", v).apply() }

    var lastBackup: Long
        get() = sp.getLong("lastBackup", 0L)
        set(v) { sp.edit().putLong("lastBackup", v).apply() }

    var lastChange: Long
        get() = sp.getLong("lastChange", 0L)
        set(v) { sp.edit().putLong("lastChange", v).apply() }
}

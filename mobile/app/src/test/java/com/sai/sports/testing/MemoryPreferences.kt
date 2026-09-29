package com.sai.sports.testing

import android.content.SharedPreferences

/** Just enough SharedPreferences for the stores under test, without Android. */
class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun getAll(): MutableMap<String, *> = values
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { values[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { this@MemoryPreferences.values[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { values[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { values[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { values[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { values[key!!] = value }
        override fun remove(key: String?) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}

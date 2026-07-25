package com.stepstracker.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Offline cache for data the app needs before/without a network round-trip: the signed-in user (so a cold start
// with no connection opens straight to Today instead of bouncing to the login screen), the weight history (so the
// weight chart renders offline) and the timestamp of the last successful sync. Encrypted like SessionStore because
// `me` carries personal data (weight, height, birth date).
class LocalCache(context:Context) {
    private val prefs=EncryptedSharedPreferences.create(context,"cache",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val json=Json { ignoreUnknownKeys=true }
    var me:Me?
        get()=prefs.getString("me",null)?.let { runCatching { json.decodeFromString<Me>(it) }.getOrNull() }
        set(value){ prefs.edit().apply { if(value==null)remove("me") else putString("me",json.encodeToString(value)) }.apply() }
    var weights:List<WeightEntry>
        get()=prefs.getString("weights",null)?.let { runCatching { json.decodeFromString<List<WeightEntry>>(it) }.getOrNull() } ?: emptyList()
        set(value){ prefs.edit().putString("weights",json.encodeToString(value)).apply() }
    var lastSyncServerTime:String?
        get()=prefs.getString("last-sync",null)
        set(value){ prefs.edit().apply { if(value==null)remove("last-sync") else putString("last-sync",value) }.apply() }
    fun clear()=prefs.edit().clear().apply()
}

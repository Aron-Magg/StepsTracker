package com.stepstracker.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context:Context) {
    private val prefs=EncryptedSharedPreferences.create(context,"session",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val accessToken get()=prefs.getString("access",null)
    val refreshToken get()=prefs.getString("refresh",null)
    val isLoggedIn get()=refreshToken!=null
    fun save(tokens:Tokens)=prefs.edit().putString("access",tokens.accessToken).putString("refresh",tokens.refreshToken).apply()
    fun clear()=prefs.edit().clear().apply()
}


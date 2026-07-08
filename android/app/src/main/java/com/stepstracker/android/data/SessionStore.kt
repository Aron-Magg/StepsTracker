package com.stepstracker.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.stepstracker.android.BuildConfig
import java.net.URI

class SessionStore(context:Context) {
    private val prefs=EncryptedSharedPreferences.create(context,"session",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val accessToken get()=prefs.getString("access",null)
    val refreshToken get()=prefs.getString("refresh",null)
    val isLoggedIn get()=refreshToken!=null
    fun save(tokens:Tokens)=prefs.edit().putString("access",tokens.accessToken).putString("refresh",tokens.refreshToken).apply()
    fun clear()=prefs.edit().clear().apply()
}

class ServerSettings(context:Context) {
    private val prefs=context.getSharedPreferences("server-settings",Context.MODE_PRIVATE)
    var baseUrl:String
        get()=prefs.getString("base-url",BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL
        private set(value){prefs.edit().putString("base-url",value).apply()}

    fun normalize(value:String):String {
        val normalized=value.trim().let { if(it.endsWith('/'))it else "$it/" }
        val uri=runCatching { URI(normalized) }.getOrElse { throw IllegalArgumentException("Invalid server URL") }
        require(uri.host!=null) { "Enter a complete server URL" }
        require(uri.scheme=="https" || (BuildConfig.DEBUG && uri.scheme=="http")) { "HTTPS is required for release builds" }
        return normalized
    }

    fun save(value:String):Boolean {
        val normalized=normalize(value);val changed=normalized!=baseUrl;baseUrl=normalized;return changed
    }
}

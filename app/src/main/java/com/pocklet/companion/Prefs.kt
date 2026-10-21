package com.pocklet.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore(name = "pocklet")

/** Pengaturan: pilihan aplikasi & model (DataStore), API key & token (terenkripsi). */
object Prefs {
    private val SELECTED = stringSetPreferencesKey("selected_apps")
    private val MODEL = stringPreferencesKey("model")
    private val AUTOSTART = booleanPreferencesKey("autostart")
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    @Volatile private var secure: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        secure?.let { return it }
        synchronized(this) {
            secure?.let { return it }
            val app = ctx.applicationContext
            val master = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sp = EncryptedSharedPreferences.create(
                app,
                "pocklet_secure",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            secure = sp
            return sp
        }
    }

    fun apiKey(ctx: Context): String = prefs(ctx).getString("api_key", "").orEmpty()

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString("api_key", key.trim()).apply()
    }

    fun token(ctx: Context): String =
        prefs(ctx).getString("token", null) ?: newToken(ctx)

    fun newToken(ctx: Context): String {
        val rnd = SecureRandom()
        val t = buildString { repeat(24) { append(CHARS[rnd.nextInt(CHARS.length)]) } }
        prefs(ctx).edit().putString("token", t).apply()
        return t
    }

    fun selectedApps(ctx: Context): Flow<Set<String>> =
        ctx.dataStore.data.map { it[SELECTED] ?: emptySet() }

    suspend fun setSelectedApps(ctx: Context, apps: Set<String>) {
        ctx.dataStore.edit { it[SELECTED] = apps }
    }

    fun model(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[MODEL].orEmpty() }

    suspend fun modelNow(ctx: Context): String =
        ctx.dataStore.data.first()[MODEL].orEmpty()

    suspend fun setModel(ctx: Context, model: String) {
        ctx.dataStore.edit { it[MODEL] = model }
    }

    fun autostart(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[AUTOSTART] ?: false }

    suspend fun setAutostart(ctx: Context, value: Boolean) {
        ctx.dataStore.edit { it[AUTOSTART] = value }
    }
}

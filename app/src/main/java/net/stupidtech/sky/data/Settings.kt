package net.stupidtech.sky.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class WatchKind(val label: String, val hint: String) {
    REG("Registration", "e.g. G-VSRB"),
    CALLSIGN("Callsign", "exact, e.g. RCH401"),
    HEX("ICAO hex", "e.g. ae56ab"),
    MIL_PREFIX("Military callsign prefix", "e.g. RCH, FORTE, DUKE");
}

data class WatchEntry(val kind: WatchKind, val value: String) {
    fun encode() = "${kind.name}:$value"
    val display get() = "${kind.label}: $value"

    companion object {
        fun decode(s: String): WatchEntry? {
            val i = s.indexOf(':')
            if (i < 0) return null
            val kind = runCatching { WatchKind.valueOf(s.substring(0, i)) }.getOrNull() ?: return null
            return WatchEntry(kind, s.substring(i + 1))
        }
    }
}

private val Context.store by preferencesDataStore("stupidsky")

class Settings(private val context: Context) {
    private val watchKey = stringSetPreferencesKey("watchlist")
    private val openSkyOnKey = booleanPreferencesKey("opensky_on")
    private val logosKey = booleanPreferencesKey("logos_on")

    val logosEnabled: Flow<Boolean> = context.store.data.map { it[logosKey] ?: true }

    suspend fun setLogosEnabled(on: Boolean) = context.store.edit { it[logosKey] = on }
    private val openSkyIdKey = stringPreferencesKey("opensky_id")
    private val openSkySecretKey = stringPreferencesKey("opensky_secret")

    val openSkyEnabled: Flow<Boolean> = context.store.data.map { it[openSkyOnKey] ?: true }

    val openSkyCredentials: Flow<OpenSkyCredentials?> = context.store.data.map { prefs ->
        OpenSkyCredentials(prefs[openSkyIdKey].orEmpty(), prefs[openSkySecretKey].orEmpty()).takeIf { it.isSet }
    }

    suspend fun setOpenSkyEnabled(on: Boolean) = context.store.edit { it[openSkyOnKey] = on }

    suspend fun setOpenSkyCredentials(id: String, secret: String) = context.store.edit {
        it[openSkyIdKey] = id.trim()
        it[openSkySecretKey] = secret.trim()
    }

    val watchlist: Flow<List<WatchEntry>> = context.store.data.map { prefs ->
        prefs[watchKey].orEmpty().mapNotNull(WatchEntry::decode).sortedBy { it.display }
    }

    suspend fun addWatch(e: WatchEntry) = context.store.edit {
        it[watchKey] = it[watchKey].orEmpty() + e.encode()
    }

    suspend fun removeWatch(e: WatchEntry) = context.store.edit {
        it[watchKey] = it[watchKey].orEmpty() - e.encode()
    }

    /** Last time we alerted on a given aircraft for a given entry, so we don't spam. */
    suspend fun lastAlerted(id: String): Long =
        context.store.data.first()[longPreferencesKey("alerted_$id")] ?: 0L

    suspend fun markAlerted(id: String, at: Long) = context.store.edit {
        it[longPreferencesKey("alerted_$id")] = at
    }
}

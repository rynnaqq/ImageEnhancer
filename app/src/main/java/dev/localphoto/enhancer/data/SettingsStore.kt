package dev.localphoto.enhancer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.localphoto.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("preferences")

class SettingsStore(private val context: Context) {
    private val defaultsKey = stringPreferencesKey("enhancement_defaults")
    val defaults: Flow<EnhanceSettings> = context.preferences.data.map {
        SettingsCodec.decode(it[defaultsKey] ?: "{}")
    }
    suspend fun save(settings: EnhanceSettings) {
        context.preferences.edit { it[defaultsKey] = SettingsCodec.encode(settings) }
    }
}

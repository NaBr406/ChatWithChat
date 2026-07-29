package cn.nabr.chatwithchat.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.memory.MemoryModelPreferenceInvalidReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingDataSourceImplTest {
    @Test
    fun `memory model preference persists and clears its fixed pair atomically`() = runBlocking {
        val dataStore = InMemoryPreferencesDataStore()
        val dataSource = SettingDataSourceImpl(dataStore)

        assertEquals(MemoryModelPreference.Auto, dataSource.getMemoryModelPreference())
        dataSource.updateMemoryModelPreference(MemoryModelPreference.Fixed("platform-1", "shared-model"))

        assertEquals(1, dataStore.updateCount)
        assertEquals(
            MemoryModelPreference.Fixed("platform-1", "shared-model"),
            SettingDataSourceImpl(dataStore).getMemoryModelPreference()
        )

        dataSource.updateMemoryModelPreference(MemoryModelPreference.Auto)

        assertEquals(2, dataStore.updateCount)
        assertEquals(MemoryModelPreference.Auto, SettingDataSourceImpl(dataStore).getMemoryModelPreference())
    }

    @Test
    fun `partial and blank stored memory model pairs remain typed invalid preferences`() = runBlocking {
        val dataStore = InMemoryPreferencesDataStore()
        val platformKey = stringPreferencesKey("memory_model_platform_uid")
        val modelKey = stringPreferencesKey("memory_model_id")

        dataStore.edit { preferences -> preferences[platformKey] = "platform-1" }
        assertEquals(
            MemoryModelPreference.Invalid(
                platformUid = "platform-1",
                modelId = null,
                reason = MemoryModelPreferenceInvalidReason.MISSING_MODEL_ID
            ),
            SettingDataSourceImpl(dataStore).getMemoryModelPreference()
        )

        dataStore.edit { preferences ->
            preferences[platformKey] = " "
            preferences[modelKey] = " "
        }
        assertEquals(
            MemoryModelPreference.Invalid(
                platformUid = " ",
                modelId = " ",
                reason = MemoryModelPreferenceInvalidReason.BLANK_PAIR
            ),
            SettingDataSourceImpl(dataStore).getMemoryModelPreference()
        )
    }

    @Test
    fun `tool preferences persist explicit enabled and disabled overrides`() = runBlocking {
        val dataStore = InMemoryPreferencesDataStore()
        val dataSource = SettingDataSourceImpl(dataStore)

        assertTrue(dataSource.getEnabledToolNames().isEmpty())
        assertTrue(dataSource.getDisabledToolNames().isEmpty())

        dataSource.updateToolEnabled("current_datetime", enabled = false)
        dataSource.updateToolEnabled("device_location", enabled = false)
        assertEquals(setOf("current_datetime", "device_location"), dataSource.getDisabledToolNames())

        dataSource.updateToolEnabled("current_datetime", enabled = true)
        assertEquals(setOf("current_datetime"), dataSource.getEnabledToolNames())
        assertEquals(setOf("device_location"), dataSource.getDisabledToolNames())

        dataSource.updateToolEnabled("current_datetime", enabled = false)
        val reloadedDataSource = SettingDataSourceImpl(dataStore)
        assertTrue(reloadedDataSource.getEnabledToolNames().isEmpty())
        assertEquals(setOf("current_datetime", "device_location"), reloadedDataSource.getDisabledToolNames())
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var updateCount: Int = 0
            private set

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            updateCount += 1
            return updated
        }
    }
}

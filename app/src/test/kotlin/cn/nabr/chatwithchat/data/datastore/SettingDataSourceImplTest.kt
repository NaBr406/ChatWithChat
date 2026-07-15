package cn.nabr.chatwithchat.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingDataSourceImplTest {
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

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}

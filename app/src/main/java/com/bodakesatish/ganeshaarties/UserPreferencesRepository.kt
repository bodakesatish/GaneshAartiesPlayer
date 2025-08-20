package com.bodakesatish.ganeshaarties

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val IS_FIRST_TIME_OPEN = booleanPreferencesKey("is_first_time_open")
        val SELECTED_THEME = intPreferencesKey("selected_theme") // Key for the theme
    }

    val isFirstTimeOpen: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            // If the key is not present, it's considered the first time, so return true.
            preferences[PreferencesKeys.IS_FIRST_TIME_OPEN] ?: true
        }

    val selectedTheme: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_THEME] ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    suspend fun updateFirstTimeOpen(isFirstTime: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_TIME_OPEN] = isFirstTime
        }
    }

    suspend fun updateSelectedTheme(themeMode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_THEME] = themeMode
        }
    }
}

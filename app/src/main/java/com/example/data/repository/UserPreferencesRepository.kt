package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.priceFloorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pricefloor_region_prefs"
)

data class RegionPreferences(
    val countryCode: String = "IE",
    val postcode: String = "",
    val currency: String = "EUR",
    val rentalDurationDays: Int = 7
)

class UserPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.priceFloorDataStore

    private object Keys {
        val COUNTRY = stringPreferencesKey("country_code")
        val POSTCODE = stringPreferencesKey("postcode")
        val CURRENCY = stringPreferencesKey("currency")
        val RENTAL_DAYS = intPreferencesKey("rental_duration_days")
    }

    val preferencesFlow: Flow<RegionPreferences> = dataStore.data.map { prefs ->
        RegionPreferences(
            countryCode = prefs[Keys.COUNTRY] ?: "IE",
            postcode = prefs[Keys.POSTCODE] ?: "",
            currency = prefs[Keys.CURRENCY] ?: "EUR",
            rentalDurationDays = prefs[Keys.RENTAL_DAYS] ?: 7
        )
    }

    suspend fun updateRegion(countryCode: String, postcode: String, currency: String, rentalDays: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.COUNTRY] = countryCode.uppercase().take(5).ifBlank { "IE" }
            prefs[Keys.POSTCODE] = postcode.trim().take(20)
            prefs[Keys.CURRENCY] = currency.uppercase().take(3).ifBlank { "EUR" }
            prefs[Keys.RENTAL_DAYS] = rentalDays.coerceIn(1, 365)
        }
    }
}

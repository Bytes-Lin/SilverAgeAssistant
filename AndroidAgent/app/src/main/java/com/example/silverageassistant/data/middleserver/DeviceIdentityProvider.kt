package com.example.silverageassistant.data.middleserver

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

class DeviceIdentityProvider(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        preferences.getString(DEVICE_ID, null)?.let { return it }
        return "android:${UUID.randomUUID()}".also { generated ->
            preferences.edit(commit = true) { putString(DEVICE_ID, generated) }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "silverage_device_identity"
        const val DEVICE_ID = "device_id"
    }
}

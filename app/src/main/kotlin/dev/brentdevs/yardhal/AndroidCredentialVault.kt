package dev.brentdevs.yardhal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.brentdevs.yardhal.core.data.CredentialVault

class AndroidCredentialVault(context: Context) : CredentialVault {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "yardhal-vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun storePassword(key: String, password: String) {
        prefs.edit().putString(key, password).apply()
    }

    override fun readPassword(key: String): String? = prefs.getString(key, null)

    override fun deletePassword(key: String) {
        prefs.edit().remove(key).apply()
    }
}

package dev.brentdevs.yardhal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.brentdevs.yardhal.core.data.CredentialVault
import java.io.IOException

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
        if (!prefs.edit().putString(key, password).commit()) throw IOException("Unable to save protected credentials")
    }

    override fun readPassword(key: String): String? = prefs.getString(key, null)

    override fun deletePassword(key: String) {
        if (!prefs.edit().remove(key).commit()) throw IOException("Unable to remove protected credentials")
    }
}

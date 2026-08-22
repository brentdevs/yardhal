package dev.brentdevs.yardhal

import android.app.Application
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YardhalApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var networkStore: NetworkStore
        private set
    lateinit var messageStore: MessageStore
        private set
    lateinit var readMarkerStore: ReadMarkerStore
        private set
    lateinit var muteStore: MuteStore
        private set
    lateinit var vault: CredentialVault
        private set

    override fun onCreate() {
        super.onCreate()
        val dir = filesDir
        networkStore = NetworkStore(dir)
        val db = YardhalDatabase.build(this)
        messageStore = MessageStore(db.messageDao())
        readMarkerStore = ReadMarkerStore(dir)
        muteStore = MuteStore(dir)
        vault = AndroidCredentialVault(this)
    }
}

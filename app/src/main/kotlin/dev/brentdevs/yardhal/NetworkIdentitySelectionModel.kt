package dev.brentdevs.yardhal

import androidx.lifecycle.ViewModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class NetworkIdentitySelection(
    public val editorId: String? = null,
    public val requestId: String? = null,
    public val busy: Boolean = false,
    public val alias: String? = null,
    public val error: String? = null,
)

public class NetworkIdentitySelectionModel : ViewModel() {
    private val mutableSelection = MutableStateFlow(NetworkIdentitySelection())
    public val selection: StateFlow<NetworkIdentitySelection> = mutableSelection.asStateFlow()

    @Synchronized
    public fun begin(editorId: String): String {
        val requestId = UUID.randomUUID().toString()
        mutableSelection.value = NetworkIdentitySelection(editorId, requestId, busy = true)
        return requestId
    }

    @Synchronized
    public fun complete(requestId: String, alias: String?) {
        val current = mutableSelection.value
        if (current.requestId != requestId || !current.busy) return
        mutableSelection.value = current.copy(
            busy = false,
            alias = alias,
            error = if (alias == null) {
                "No identity was selected. Choose an installed identity or import a PKCS#12 (.p12 or .pfx) file."
            } else {
                null
            },
        )
    }

    @Synchronized
    public fun fail(requestId: String) {
        val current = mutableSelection.value
        if (current.requestId != requestId || !current.busy) return
        mutableSelection.value = current.copy(
            busy = false,
            error = "Android couldn't open identity selection. Check that a certificate with a private key is installed, then try again.",
        )
    }

    @Synchronized
    public fun consume(requestId: String) {
        val current = mutableSelection.value
        if (current.requestId == requestId && !current.busy) {
            mutableSelection.value = NetworkIdentitySelection()
        }
    }
}

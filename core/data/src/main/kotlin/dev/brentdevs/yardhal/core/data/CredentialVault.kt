package dev.brentdevs.yardhal.core.data

public interface CredentialVault {
    public fun storePassword(key: String, password: String)
    public fun readPassword(key: String): String?
    public fun deletePassword(key: String)
}

public class InMemoryCredentialVault : CredentialVault {
    private val passwords = LinkedHashMap<String, String>()

    override fun storePassword(key: String, password: String) {
        synchronized(passwords) { passwords[key] = password }
    }

    override fun readPassword(key: String): String? = synchronized(passwords) { passwords[key] }

    override fun deletePassword(key: String) {
        synchronized(passwords) { passwords.remove(key) }
    }
}

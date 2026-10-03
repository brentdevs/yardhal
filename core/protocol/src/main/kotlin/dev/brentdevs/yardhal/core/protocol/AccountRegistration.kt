package dev.brentdevs.yardhal.core.protocol

public data class AccountRegistrationPolicy(
    public val beforeConnect: Boolean = false,
    public val emailRequired: Boolean = false,
    public val customAccountName: Boolean = false,
    public val minPasswordLength: Int? = null,
    public val maxPasswordLength: Int? = null,
) {
    public companion object {
        public const val CAPABILITY: String = "draft/account-registration"

        public fun parse(value: String?): AccountRegistrationPolicy {
            if (value.isNullOrEmpty()) return AccountRegistrationPolicy()
            var beforeConnect = false
            var emailRequired = false
            var customAccountName = false
            var minPasswordLength: Int? = null
            var maxPasswordLength: Int? = null
            for (token in value.split(',')) {
                val key = token.substringBefore('=')
                val tokenValue = token.substringAfter('=', "")
                when (key) {
                    "before-connect" -> beforeConnect = true
                    "email-required" -> emailRequired = true
                    "custom-account-name" -> customAccountName = true
                    "min-password-length" -> minPasswordLength = positiveIntOrNull(tokenValue)
                    "max-password-length" -> maxPasswordLength = positiveIntOrNull(tokenValue)
                }
            }
            return AccountRegistrationPolicy(
                beforeConnect = beforeConnect,
                emailRequired = emailRequired,
                customAccountName = customAccountName,
                minPasswordLength = minPasswordLength,
                maxPasswordLength = maxPasswordLength,
            )
        }

        private fun positiveIntOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it > 0 }
    }
}

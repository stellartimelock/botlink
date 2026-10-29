package org.stellartimelock.botlink.protocol

import java.net.URLEncoder

/**
 * x402 v2 exact payments on Stellar, signed by a wallet — never by Bot Link.
 *
 * Bot Link builds the challenge and a SEP-7 URI. StellarTimeLock (or Scopuly)
 * holds the keys and returns signed XDR on `botlink://x402/signed`.
 *
 * Challenge field names match Scopuly's live PAYMENT-REQUIRED header.
 */
object X402 {
    const val ENABLED: Boolean = true
    const val PROTOCOL: String = "botlink.x402.sep7"
    const val REQUEST_VERSION: Int = 1
    const val X402_VERSION: Int = 2
    const val SCHEME: String = "exact"
    const val NETWORK_PUBNET: String = "stellar:pubnet"
    const val NETWORK_TESTNET: String = "stellar:testnet"
    const val USDC_SAC_PUBNET: String = "CCW67TSZV3SSS2HXMBQ5JFGCKJNXKZM7UQUWUZPUTHXSTZLEO7SJMI75"
    const val USDC_SAC_TESTNET: String = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    const val USDC_ISSUER_PUBNET: String = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    const val ASSET_CODE: String = "USDC"
    const val TIMEOUT_SECONDS: Int = 300
    const val CALLBACK: String = "botlink://x402/signed"
    const val WALLET_PACKAGE: String = "com.stellartimelock"
    const val SCOPULY_PACKAGE: String = "com.sdex.app"
    const val ORIGIN_DOMAIN: String = "stellartimelock.com"

    /** Spec example G-address for docs and tests only. Not a live house account. */
    const val EXAMPLE_PAY_TO: String = "GBHEGW3KWOY2OFH767EDALFGCUTBOEVBDQMCKU4APMDLQNBW5QV3W3KO"
    const val EXAMPLE_NOTE_ID: String = "11111111-1111-1111-1111-111111111111"
    private val G_ADDRESS = Regex("^G[A-Z2-7]{55}$")

    fun isGAddress(value: String): Boolean = G_ADDRESS.matches(value.trim())

    data class SignRequest(
        val v: Int = REQUEST_VERSION,
        val protocol: String = PROTOCOL,
        val x402Version: Int = X402_VERSION,
        val paymentRequired: Map<String, Any>,
        val sep7: String,
        val callback: String = CALLBACK,
        val walletPackage: String = WALLET_PACKAGE,
    ) {
        fun encode(): String {
            val body = LinkedHashMap<String, Any>()
            body["v"] = v
            body["protocol"] = protocol
            body["x402Version"] = x402Version
            body["paymentRequired"] = paymentRequired
            body["sep7"] = sep7
            body["callback"] = callback
            body["walletPackage"] = walletPackage
            return encodeValue(body)
        }
    }

    fun atomicAmount(cents: Int): String = (cents.toLong() * 100_000L).toString()

    fun displayAmount(cents: Int): String = "%d.%02d00000".format(cents / 100, cents % 100)

    fun asset(network: String): String =
        if (network == NETWORK_TESTNET) USDC_SAC_TESTNET else USDC_SAC_PUBNET

    fun paymentRequired(
        noteId: String,
        cents: Int,
        payTo: String,
        network: String = NETWORK_PUBNET,
        agent: String = "grok-bot",
    ): LinkedHashMap<String, Any> {
        val resource = LinkedHashMap<String, Any>()
        resource["url"] = "botlink://spend/$noteId"
        resource["description"] = "Bot Link agent spend"
        resource["mimeType"] = "application/json"
        resource["serviceName"] = "Bot Link"
        resource["tags"] = listOf("stellar", "x402", "usdc")

        val extra = LinkedHashMap<String, Any>()
        extra["areFeesSponsored"] = true
        extra["cents"] = cents
        extra["agent"] = agent

        val accepted = LinkedHashMap<String, Any>()
        accepted["scheme"] = SCHEME
        accepted["network"] = network
        accepted["asset"] = asset(network)
        accepted["amount"] = atomicAmount(cents)
        accepted["payTo"] = payTo
        accepted["maxTimeoutSeconds"] = TIMEOUT_SECONDS
        accepted["extra"] = extra

        val body = LinkedHashMap<String, Any>()
        body["x402Version"] = X402_VERSION
        body["resource"] = resource
        body["accepts"] = listOf(accepted)
        body["error"] = "Payment required"
        return body
    }

    fun sep7Uri(
        cents: Int,
        payTo: String,
        noteId: String,
        network: String = NETWORK_PUBNET,
    ): String {
        val memo = "bl:" + noteId.replace("-", "").take(8)
        val msg = "Bot Link spend \$" + "%d.%02d".format(cents / 100, cents % 100)
        val params = LinkedHashMap<String, String>()
        params["destination"] = payTo
        params["amount"] = displayAmount(cents)
        params["asset_code"] = ASSET_CODE
        if (network != NETWORK_TESTNET) {
            params["asset_issuer"] = USDC_ISSUER_PUBNET
        }
        params["memo"] = memo
        params["msg"] = msg
        params["origin_domain"] = ORIGIN_DOMAIN
        params["callback"] = "url:$CALLBACK"
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "web+stellar:pay?$query"
    }

    fun signRequest(
        noteId: String,
        cents: Int,
        payTo: String,
        network: String = NETWORK_PUBNET,
        agent: String = "grok-bot",
    ): SignRequest = SignRequest(
        paymentRequired = paymentRequired(noteId, cents, payTo, network, agent),
        sep7 = sep7Uri(cents, payTo, noteId, network),
    )

    fun example(): SignRequest = signRequest(EXAMPLE_NOTE_ID, 40, EXAMPLE_PAY_TO)

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    internal fun encodeValue(value: Any): String = when (value) {
        is String -> jsonString(value)
        is Int -> value.toString()
        is Long -> value.toString()
        is Boolean -> if (value) "true" else "false"
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encodeValue(it!!)
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            jsonString(k.toString()) + ":" + encodeValue(v!!)
        }
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }
}

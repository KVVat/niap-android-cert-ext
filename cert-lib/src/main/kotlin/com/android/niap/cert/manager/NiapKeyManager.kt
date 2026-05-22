package com.android.niap.cert.manager

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Standard [X509ExtendedKeyManager] backed by an enrolled certificate whose
 * private key lives in the cert-manager service. Callers can plug this
 * directly into any [javax.net.ssl.SSLContext] / OkHttp / HttpsURLConnection
 * stack — the signing-oracle plumbing is hidden behind the standard JCA
 * interface.
 *
 * Construction is cheap; the actual signing happens lazily inside
 * [RemoteSignatureSpi] via [RemotePrivateKey.signingFn] when the TLS stack
 * calls Signature.sign().
 */
internal class NiapKeyManager(
    private val alias: String,
    private val certificateChain: Array<X509Certificate>,
    signingFn: (ByteArray) -> ByteArray
) : X509ExtendedKeyManager() {

    private val remoteKey: RemotePrivateKey

    init {
        require(certificateChain.isNotEmpty()) { "Empty certificate chain for alias '$alias'" }
        val params = (certificateChain[0].publicKey as? ECPublicKey)?.params
            ?: throw IllegalArgumentException(
                "NiapKeyManager only supports EC keys; cert for '$alias' has " +
                "${certificateChain[0].publicKey.algorithm}"
            )
        remoteKey = RemotePrivateKey(alias, params, signingFn)
    }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = certificateChain
    override fun getPrivateKey(alias: String?): PrivateKey = remoteKey

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
}

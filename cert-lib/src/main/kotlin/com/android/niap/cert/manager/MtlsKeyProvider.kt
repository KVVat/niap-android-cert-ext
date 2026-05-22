package com.android.niap.cert.manager

import java.security.cert.X509Certificate

/**
 * Abstraction over key storage backends for mTLS client authentication.
 *
 * Implementations:
 *  - Cert-manager backed (service-resident keys, signing via AIDL) — the default,
 *    wired through [NiapCertManager.getKeyManager] and [NiapKeyManager].
 *  - [KeyChainMtlsKeyProvider]: system KeyChain (keys accessible cross-process).
 *    Not yet implemented — kept as a stub for a future installToKeyChain() flow.
 */
interface MtlsKeyProvider {
    fun getCertificateChain(alias: String): Array<X509Certificate>?
    /** Sign pre-hashed digest bytes; algorithm is NONEwithECDSA (Conscrypt convention). */
    fun sign(alias: String, digestBytes: ByteArray): ByteArray
}

/**
 * KeyChain-backed provider: the key lives in the system credential store and is
 * accessible cross-process, so signing runs directly in the caller's process.
 *
 * TODO: implement once KeyChain enrollment flow is added.
 */
class KeyChainMtlsKeyProvider(private val context: android.content.Context) : MtlsKeyProvider {
    override fun getCertificateChain(alias: String): Array<X509Certificate>? =
        android.security.KeyChain.getCertificateChain(context, alias)

    override fun sign(alias: String, digestBytes: ByteArray): ByteArray {
        throw UnsupportedOperationException("KeyChain signing not yet implemented")
    }
}

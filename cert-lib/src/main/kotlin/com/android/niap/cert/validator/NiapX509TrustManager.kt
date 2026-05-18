package com.android.niap.cert.validator

import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

class NiapX509TrustManager(
    private val delegate: X509TrustManager,
    private val validator: NiapCertValidator = NiapCertValidator()
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(chain, authType)
        if (chain != null) {
            validator.validateCertificate(chain)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return delegate.acceptedIssuers
    }
}

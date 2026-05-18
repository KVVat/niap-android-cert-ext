package com.android.niap.cert.service.engine

import android.util.Log
import com.android.niap.cert.validator.NiapCertValidator
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import com.android.niap.cert.manager.ValidatorConfig

class CertValidator {

    fun validate(certData: ByteArray, config: ValidatorConfig): Boolean {
        try {
            Log.d("CertValidator", "Validating received certificate data (${certData.size} bytes)")
            val factory = CertificateFactory.getInstance("X.509")
            
            // Try to Base64 decode the response if it is Base64 encoded, otherwise use raw bytes.
            val decodedData = try {
                android.util.Base64.decode(certData, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                certData
            }

            // PKCS#7 responses contain multiple certificates in a CMS signed data block.
            // We must parse all certs in the chain using generateCertificates.
            val certificates = factory.generateCertificates(ByteArrayInputStream(decodedData))
            val certsChain = certificates.filterIsInstance<X509Certificate>().toTypedArray()
            
            if (certsChain.isEmpty()) {
                throw java.security.cert.CertificateException("No certificates found in PKCS#7 response")
            }

            Log.d("CertValidator", "Parsed chain of ${certsChain.size} certificates, initiating validation")
            val validator = NiapCertValidator(
                strictSigAlg = config.strictSigAlg,
                enforceCaConstraints = config.enforceCaConstraints,
                enforceMandatoryExtensions = config.enforceMandatoryExtensions,
                enforceEku = config.enforceEku
            )
            validator.validateCertificate(certsChain)
            Log.d("CertValidator", "Certificate validation passed successfully")
            return true
        } catch (e: Exception) {
            Log.e("CertValidator", "Certificate validation failed: ${e.message}")
            return false
        }
    }
}

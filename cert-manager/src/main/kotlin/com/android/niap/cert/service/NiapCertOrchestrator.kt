package com.android.niap.cert.service

import android.content.Context
import android.util.Log
import com.android.niap.cert.manager.EnrollmentRequest
import com.android.niap.cert.service.engine.CsrEngine
import com.android.niap.cert.service.engine.EstClient
import com.android.niap.cert.service.engine.KeyStoreEngine
import com.android.niap.cert.service.engine.CertValidator
import java.util.concurrent.ConcurrentHashMap

class NiapCertOrchestrator(private val context: Context) {
    private val csrEngine = CsrEngine()
    private val estClient = EstClient(context)
    private val validator = CertValidator()
    private val keyStoreEngine = KeyStoreEngine()
    private val statuses = ConcurrentHashMap<String, String>()
    private val errorMessages = ConcurrentHashMap<String, String>()

    suspend fun executeWorkflow(request: EnrollmentRequest) {
        val alias = request.alias
        errorMessages.remove(alias)
        statuses[alias] = "GENERATING_CSR"
        Log.d("NiapCertOrchestrator", "Generating CSR for $alias")
        val csr = csrEngine.generateCsr(alias, request.subjectDn, request.sans, request.csrSpec)

        statuses[alias] = "ACQUIRING_CERT"
        Log.d("NiapCertOrchestrator", "Acquiring certificate via EST from ${request.estServerUrl}")
        val certResponse = estClient.acquireCertificate(csr, request.estServerUrl, request.authToken, request.trustedCaPem)

        if (certResponse.isEmpty()) {
            statuses[alias] = "NETWORK_ERROR"
            val errMsg = estClient.lastError ?: "Connection refused or unreachable"
            errorMessages[alias] = errMsg
            Log.e("NiapCertOrchestrator", "Network error during EST certificate acquisition: $errMsg")
            return
        }

        statuses[alias] = "VALIDATING_CERT"
        Log.d("NiapCertOrchestrator", "Validating received certificate")
        val isValid = validator.validate(certResponse, request.validatorConfig)

        if (isValid) {
            statuses[alias] = "STORING_CERT"
            Log.d("NiapCertOrchestrator", "Storing certificate in KeyStore")
            keyStoreEngine.storeCertificate(alias, certResponse)
            statuses[alias] = "READY"
        } else {
            statuses[alias] = "VALIDATION_FAILED"
            errorMessages[alias] = validator.lastError ?: "Certificate validation failed"
        }
    }

    fun getStatus(alias: String): String {
        return statuses[alias] ?: "UNKNOWN"
    }

    fun getCertificateData(alias: String): ByteArray {
        return keyStoreEngine.getCertificateData(alias)
    }

    fun getErrorMessage(alias: String): String {
        return errorMessages[alias] ?: "No detailed error available"
    }

    suspend fun revoke(alias: String) {
        statuses[alias] = "REVOKING"
        errorMessages.remove(alias)
        estClient.revokeCertificate(alias)
        keyStoreEngine.deleteCertificate(alias)
        statuses[alias] = "REVOKED"
    }
}

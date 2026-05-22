package com.android.niap.cert.service

import android.content.Context
import android.util.Log
import com.android.niap.cert.manager.EnrollmentRequest
import com.android.niap.cert.service.engine.CsrEngine
import com.android.niap.cert.service.engine.EstClient
import com.android.niap.cert.service.engine.KeyStoreEngine
import com.android.niap.cert.service.engine.CertValidator
class NiapCertOrchestrator(private val context: Context) {
    private val csrEngine = CsrEngine()
    private val estClient = EstClient(context)
    private val validator = CertValidator()
    private val keyStoreEngine = KeyStoreEngine()

    data class WorkflowResult(
        val certificateData: ByteArray?,
        val errorMessage: String?
    )

    suspend fun executeWorkflow(request: EnrollmentRequest): WorkflowResult {
        val alias = request.alias
        Log.d("NiapCertOrchestrator", "Generating CSR for $alias")
        val csr = csrEngine.generateCsr(alias, request.subjectDn, request.sans, request.csrSpec)

        Log.d("NiapCertOrchestrator", "Acquiring certificate via EST from ${request.estServerUrl}")
        val certResponse = estClient.acquireCertificate(csr, request.estServerUrl, request.authToken, request.trustedCaPem)

        if (certResponse.isEmpty()) {
            val errMsg = estClient.lastError ?: "Connection refused or unreachable"
            Log.e("NiapCertOrchestrator", "Network error during EST certificate acquisition: $errMsg")
            return WorkflowResult(null, errMsg)
        }

        Log.d("NiapCertOrchestrator", "Validating received certificate")
        val isValid = validator.validate(certResponse, request.validatorConfig)

        return if (isValid) {
            Log.d("NiapCertOrchestrator", "Storing certificate in KeyStore")
            keyStoreEngine.storeCertificate(alias, certResponse)
            WorkflowResult(keyStoreEngine.getCertificateData(alias), null)
        } else {
            val errMsg = validator.lastError ?: "Certificate validation failed"
            WorkflowResult(null, errMsg)
        }
    }

    fun hasEnrollment(alias: String): Boolean = keyStoreEngine.getCertificateData(alias).isNotEmpty()

    fun getCertificateData(alias: String): ByteArray = keyStoreEngine.getCertificateData(alias)

    suspend fun revoke(alias: String) {
        estClient.revokeCertificate(alias)
        keyStoreEngine.deleteCertificate(alias)
    }
}

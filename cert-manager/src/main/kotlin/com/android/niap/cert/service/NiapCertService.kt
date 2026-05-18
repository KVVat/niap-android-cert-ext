package com.android.niap.cert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.android.niap.cert.manager.EnrollmentRequest
import com.android.niap.cert.manager.INiapCertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NiapCertService : Service() {

    private val orchestrator by lazy { NiapCertOrchestrator(this) }
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    inner class CertBinder : INiapCertManager.Stub() {
        override fun requestCertificate(
            alias: String?,
            estServerUrl: String?,
            authToken: String?,
            subjectDn: String?,
            sans: List<String>?,
            keyTypeStr: String?,
            sigAlgStr: String?,
            trustedCaPem: String?,
            validatorConfig: android.os.Bundle?
        ) {
            if (alias == null || estServerUrl == null || authToken == null || subjectDn == null) {
                Log.e("NiapCertService", "Invalid AIDL requestCertificate: missing parameters")
                return
            }
            Log.d("NiapCertService", "Requesting certificate for alias: $alias")
            val keyType = try { com.android.niap.cert.manager.KeyType.valueOf(keyTypeStr ?: "EC_P384") } catch (e: Exception) { com.android.niap.cert.manager.KeyType.EC_P384 }
            val sigAlg = try { com.android.niap.cert.manager.SigAlg.valueOf(sigAlgStr ?: "SHA384_ECDSA") } catch (e: Exception) { com.android.niap.cert.manager.SigAlg.SHA384_ECDSA }
            
            val config = if (validatorConfig != null) {
                com.android.niap.cert.manager.ValidatorConfig(
                    strictSigAlg = validatorConfig.getBoolean("strictSigAlg", true),
                    enforceCaConstraints = validatorConfig.getBoolean("enforceCaConstraints", true),
                    enforceMandatoryExtensions = validatorConfig.getBoolean("enforceMandatoryExtensions", true),
                    enforceEku = validatorConfig.getBoolean("enforceEku", true)
                )
            } else {
                com.android.niap.cert.manager.ValidatorConfig()
            }

            val request = EnrollmentRequest(
                alias = alias,
                estServerUrl = estServerUrl,
                authToken = authToken,
                subjectDn = subjectDn,
                sans = sans ?: emptyList(),
                csrSpec = com.android.niap.cert.manager.CsrSpec(keyType, sigAlg),
                trustedCaPem = trustedCaPem ?: "",
                validatorConfig = config
            )
            serviceScope.launch {
                orchestrator.executeWorkflow(request)
            }
        }

        override fun getCertificateStatus(alias: String?): String {
            return orchestrator.getStatus(alias ?: "")
        }

        override fun getCertificateData(alias: String?): ByteArray {
            return orchestrator.getCertificateData(alias ?: "")
        }

        override fun getErrorMessage(alias: String?): String {
            return orchestrator.getErrorMessage(alias ?: "")
        }

        override fun revokeCertificate(alias: String?) {
            Log.d("NiapCertService", "Revoking certificate for alias: $alias")
            serviceScope.launch {
                orchestrator.revoke(alias ?: "")
            }
        }
    }

    private val binder = CertBinder()

    override fun onCreate() {
        super.onCreate()
        Log.d("NiapCertService", "Service created")
        createNotificationChannel()
        startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("NiapCertService", "Service bound")
        return binder
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "niap_cert_channel",
            "NIAP Certificate Manager",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, "niap_cert_channel")
            .setContentTitle("NIAP Cert Manager")
            .setContentText("Managing certificates securely")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}

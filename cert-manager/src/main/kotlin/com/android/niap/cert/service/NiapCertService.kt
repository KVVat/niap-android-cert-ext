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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

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

        override fun verifyMtls(alias: String?, protectedUrl: String?, trustedCaPem: String?): String {
            if (alias == null || protectedUrl == null) return "ERROR\nMissing alias or URL"
            return try {
                Log.d("NiapCertService", "verifyMtls: alias=$alias url=$protectedUrl")
                val cf = CertificateFactory.getInstance("X.509")
                val caCert = cf.generateCertificate((trustedCaPem ?: "").byteInputStream()) as X509Certificate
                Log.d("NiapCertService", "verifyMtls: CA cert subject=${caCert.subjectDN}")
                val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("ca", caCert)
                }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
                val trustManager = tmf.trustManagers[0] as X509TrustManager

                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val hasAlias = ks.containsAlias(alias)
                val clientCert = ks.getCertificate(alias)
                val clientKey = ks.getKey(alias, null)
                Log.d("NiapCertService", "verifyMtls: alias=$alias hasAlias=$hasAlias cert=${clientCert?.let { (it as? X509Certificate)?.subjectDN }} key=${clientKey?.algorithm}")
                if (clientCert == null || clientKey == null) {
                    return "ERROR\nAlias '$alias' missing cert=${clientCert != null} key=${clientKey != null} in AndroidKeyStore"
                }
                val keyManagers: Array<KeyManager> = arrayOf(ServiceAliasKeyManager(alias, ks))
                val sslCtx = SSLContext.getInstance("TLS").apply {
                    init(keyManagers, arrayOf(trustManager), null)
                }
                val tls12Spec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                    .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
                    .build()
                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslCtx.socketFactory, trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .connectionSpecs(listOf(tls12Spec))
                    .build()
                val response = client.newCall(Request.Builder().url(protectedUrl).get().build()).execute()
                val code = response.code
                val body = response.body?.string()?.trim() ?: ""
                Log.d("NiapCertService", "verifyMtls result: HTTP $code")
                "HTTP $code\n$body"
            } catch (e: Exception) {
                Log.e("NiapCertService", "verifyMtls error: ${e.message}", e)
                "ERROR\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private class ServiceAliasKeyManager(
        private val alias: String,
        private val keyStore: KeyStore
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) = alias
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            val cert = try { keyStore.getCertificate(this.alias) as? X509Certificate } catch (e: Exception) { null }
            Log.d("NiapCertService", "getCertificateChain(${this.alias}): cert=${cert?.subjectDN}")
            return cert?.let { arrayOf(it) }
        }
        override fun getPrivateKey(alias: String?): PrivateKey? {
            val key = try { keyStore.getKey(this.alias, null) as? PrivateKey } catch (e: Exception) { null }
            Log.d("NiapCertService", "getPrivateKey(${this.alias}): key=${key?.algorithm}")
            return key
        }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
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

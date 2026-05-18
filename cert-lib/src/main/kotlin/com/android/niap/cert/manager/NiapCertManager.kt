package com.android.niap.cert.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NiapCertManager(private val context: Context) {
    private var service: INiapCertManager? = null
    private var latch = CountDownLatch(1)

    private var isBinding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d("NiapCertManager", "Service connected")
            service = INiapCertManager.Stub.asInterface(binder)
            isBinding = false
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("NiapCertManager", "Service disconnected")
            service = null
            isBinding = false
        }
    }

    fun bindService(): Boolean {
        if (service != null || isBinding) return true
        isBinding = true
        if (latch.count == 0L) {
            latch = CountDownLatch(1)
        }
        val intent = Intent("com.android.niap.cert.service.BIND").apply {
            setPackage("com.example.niap.cert.ext.manager")
        }
        Log.d("NiapCertManager", "Initiating bindService to com.example.niap.cert.ext.manager")
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (service != null) {
            context.unbindService(connection)
            service = null
            isBinding = false
        }
    }

    fun requestCertificate(request: EnrollmentRequest) {
        ensureServiceConnected()
        val configBundle = android.os.Bundle().apply {
            putBoolean("strictSigAlg", request.validatorConfig.strictSigAlg)
            putBoolean("enforceCaConstraints", request.validatorConfig.enforceCaConstraints)
            putBoolean("enforceMandatoryExtensions", request.validatorConfig.enforceMandatoryExtensions)
            putBoolean("enforceEku", request.validatorConfig.enforceEku)
        }
        service?.requestCertificate(
            request.alias,
            request.estServerUrl,
            request.authToken,
            request.subjectDn,
            request.sans,
            request.csrSpec.keyType.name,
            request.csrSpec.sigAlg.name,
            request.trustedCaPem,
            configBundle
        )
    }

    fun getCertificateStatus(alias: String): String {
        ensureServiceConnected()
        return service?.getCertificateStatus(alias) ?: "DISCONNECTED"
    }

    fun getCertificateData(alias: String): ByteArray {
        ensureServiceConnected()
        return service?.getCertificateData(alias) ?: ByteArray(0)
    }

    fun getErrorMessage(alias: String): String {
        ensureServiceConnected()
        return service?.getErrorMessage(alias) ?: "Unknown error"
    }

    fun revokeCertificate(alias: String) {
        ensureServiceConnected()
        service?.revokeCertificate(alias)
    }

    private fun ensureServiceConnected() {
        if (service == null) {
            bindService()
            val connected = latch.await(10, TimeUnit.SECONDS)
            if (!connected) {
                Log.e("NiapCertManager", "Timeout waiting for service binding")
                isBinding = false
            }
        }
    }
}

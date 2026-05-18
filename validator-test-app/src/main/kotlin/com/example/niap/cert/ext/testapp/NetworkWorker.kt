package com.example.niap.cert.ext.testapp

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.android.niap.cert.validator.NiapCertValidator
import com.android.niap.cert.validator.NiapX509TrustManager
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import com.android.niap.cert.validator.NiapCertHelper

class NetworkWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val urlString = inputData.getString("url") ?: return Result.failure()
        val type = inputData.getString("type") ?: "http"
        Log.d("NetworkWorker", "Starting request to $urlString using $type")

        try {
            NiapCertHelper.resetInstance()
            val helper = NiapCertHelper.getInstance(applicationContext)
            Log.d("NetworkWorker", "Calling validateUrl")
            helper.validateUrl(urlString)
            Log.d("NetworkWorker", "validateUrl passed")

            if (type == "okhttp3") {
                Log.d("NetworkWorker", "Configuring OkHttp")
                val client = helper.configureOkHttp(OkHttpClient.Builder()).build()
                Log.d("NetworkWorker", "OkHttp configured")
                
                val request = Request.Builder()
                    .url(urlString)
                    .build()
                
                Log.d("NetworkWorker", "Executing OkHttp call")
                client.newCall(request).execute().use { response ->
                    Log.d("NetworkWorker", "OkHttp Response Code: ${response.code}")
                    return Result.success()
                }
            } else {
                val url = URL(urlString)
                val connection = helper.openConnection(url)
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
                    Log.d("NetworkWorker", "Verifying hostname: $hostname")
                    helper.checkHostname(hostname, session)
                    true
                }
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.connect()
                val responseCode = connection.responseCode
                Log.d("NetworkWorker", "Response Code: $responseCode")

                connection.disconnect()
                return Result.success()
            }
        } catch (e: Exception) {
            Log.e("NetworkWorker", "Connection failed", e)
            val output = androidx.work.Data.Builder()
                .putString("error", e.message)
                .build()
            return Result.failure(output)
        }
    }
}

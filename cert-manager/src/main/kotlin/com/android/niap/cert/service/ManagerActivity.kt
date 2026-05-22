package com.android.niap.cert.service

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.niap.cert.manager.EnrollmentRequest
import com.android.niap.cert.manager.NiapCertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope

class ManagerActivity : ComponentActivity() {
    private lateinit var certManager: NiapCertManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        certManager = NiapCertManager(applicationContext)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ManagerAppScreen(certManager)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra("action")) {
        "verifyMtls" -> {
            val alias = intent.getStringExtra("alias") ?: "test_client_cert"
            val protectedUrl = intent.getStringExtra("protectedUrl") ?: "https://localhost:8443/protected/"
            val caPemUrl = intent.getStringExtra("caPemUrl") ?: "http://localhost:8080/cacert.pem"
            lifecycleScope.launch(Dispatchers.IO) {
                val caPem = try {
                    java.net.URL(caPemUrl).readText()
                } catch (e: Exception) {
                    Log.w("ManagerActivity", "Could not download CA PEM: ${e.message}")
                    ""
                }
                Log.d("ManagerActivity", "Auto-verifyMtls: alias=$alias url=$protectedUrl")
                val result = certManager.verifyMtls(alias, protectedUrl, caPem)
                Log.d("ManagerActivity", "MTLS_RESULT: $result")
            }
        }
        "enroll" -> {
            val alias = intent.getStringExtra("alias") ?: "test_client_cert"
            val estUrl = intent.getStringExtra("estUrl") ?: "https://localhost:8443/.well-known/est/"
            val authToken = intent.getStringExtra("authToken") ?: "estuser:estpwd"
            val subjectDn = intent.getStringExtra("subjectDn") ?: "CN=TestUser"

            lifecycleScope.launch(Dispatchers.IO) {
                val caPemUrl = intent.getStringExtra("caPemUrl") ?: "http://localhost:8080/cacert.pem"
                val caPem = try {
                    java.net.URL(caPemUrl).readText().also {
                        Log.d("ManagerActivity", "Downloaded CA PEM from $caPemUrl (${it.length} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w("ManagerActivity", "Could not download CA PEM from $caPemUrl: ${e.message}")
                    ""
                }

                Log.d("ManagerActivity", "Auto-enroll: alias=$alias url=$estUrl subject=$subjectDn")
                val request = EnrollmentRequest(alias, estUrl, authToken, subjectDn, trustedCaPem = caPem)
                try {
                    certManager.enroll(request).get(30, java.util.concurrent.TimeUnit.SECONDS)
                    Log.d("ManagerActivity", "Enrollment succeeded: READY")
                } catch (e: java.util.concurrent.ExecutionException) {
                    Log.e("ManagerActivity", "Enrollment failed: ${e.cause?.message ?: e.message}")
                } catch (e: Exception) {
                    Log.e("ManagerActivity", "Enrollment failed: ${e.message}")
                }
            }
        }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        certManager.close()
    }
}

@Composable
fun ManagerAppScreen(certManager: NiapCertManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var alias by remember { mutableStateOf("test_client_cert") }
    var estUrl by remember { mutableStateOf("https://testrfc7030.com:8443/.well-known/est") }
    var authToken by remember { mutableStateOf("estuser:estpwd") }
    var subjectDn by remember { mutableStateOf("CN=Test User, O=Cisco EST, C=US") }

    var status by remember { mutableStateOf("IDLE") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var certSummary by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun appendLog(msg: String) {
        Log.d("ManagerActivityApp", msg)
        logMessages = logMessages + msg
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("NIAP EST Enrollment Manager", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("Key Alias") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = estUrl,
            onValueChange = { estUrl = it },
            label = { Text("EST Server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = authToken,
            onValueChange = { authToken = it },
            label = { Text("Auth Token (user:pass)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = subjectDn,
            onValueChange = { subjectDn = it },
            label = { Text("Subject DN") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    logMessages = emptyList()
                    certSummary = null
                    errorMessage = null
                    status = "ENROLLING"

                    var caPem = ""
                    appendLog("Loading trusted CA certificate from local raw resource (dstcax3.pem)...")
                    try {
                        val resId = context.resources.getIdentifier("dstcax3", "raw", context.packageName)
                        if (resId != 0) {
                            caPem = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
                            appendLog("Loaded ${caPem.length} bytes from local raw CA resource")
                        } else {
                            appendLog("Warning: Local raw resource dstcax3 not found")
                        }
                    } catch (e: Exception) {
                        appendLog("Warning: Failed to load raw CA: ${e.message}")
                    }

                    appendLog("Sending enrollment request for alias: $alias")
                    val request = EnrollmentRequest(alias, estUrl, authToken, subjectDn, trustedCaPem = caPem)
                    try {
                        val certBytes = certManager.enroll(request).get(30, java.util.concurrent.TimeUnit.SECONDS)
                        status = "READY"
                        appendLog("Enrollment succeeded; parsing certificate...")
                        val factory = CertificateFactory.getInstance("X.509")
                        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                        certSummary = """
                            Subject: ${cert.subjectDN}
                            Issuer: ${cert.issuerDN}
                            Serial: ${cert.serialNumber}
                            Algorithm: ${cert.sigAlgName}
                            Valid From: ${cert.notBefore}
                            Valid Until: ${cert.notAfter}
                        """.trimIndent()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        status = "FAILED"
                        val detail = e.cause?.message ?: e.message ?: "Unknown error"
                        appendLog("Enrollment failed: $detail")
                        errorMessage = detail
                    } catch (e: Exception) {
                        status = "FAILED"
                        appendLog("Enrollment error: ${e.message}")
                        errorMessage = e.message
                    }
                }
            }) {
                Text("Enroll (EST)")
            }

            Button(onClick = {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    appendLog("Sending revoke request for alias: $alias")
                    try {
                        certManager.revoke(alias).get(15, java.util.concurrent.TimeUnit.SECONDS)
                        status = "REVOKED"
                        certSummary = null
                        errorMessage = null
                    } catch (e: Exception) {
                        appendLog("Revoke failed: ${e.message}")
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Revoke")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Status: $status", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Enrollment Failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (certSummary != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Acquired Certificate Summary", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(certSummary!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Execution Logs:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                logMessages.forEach { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
